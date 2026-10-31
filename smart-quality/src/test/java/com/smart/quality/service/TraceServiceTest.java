package com.smart.quality.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.quality.dto.QualityDtos.TraceNode;
import com.smart.quality.dto.QualityDtos.TraceResultVO;
import com.smart.quality.entity.BatchRelation;
import com.smart.quality.entity.ProductBatch;
import com.smart.quality.mapper.BatchRelationMapper;
import com.smart.quality.mapper.ProductBatchMapper;
import com.smart.quality.support.Stub;
import com.smart.quality.support.Stub.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次追溯服务单元测试（第 6 周任务 6.2 / 6.5 / 6.6 / 第 7 周任务 7.1）。
 *
 * <h3>核心验证点</h3>
 * <ol>
 *   <li><b>反向追溯</b>：成品 → 全部上游物料批次（含供应商）</li>
 *   <li><b>正向追溯</b>：物料批次 → 全部下游产品批次（精准召回）</li>
 *   <li><b>多层遍历</b>：3 层数据能正确展开</li>
 *   <li><b>环路防护</b>：构造环形数据不会死循环/栈溢出</li>
 *   <li><b>深度限制</b>：超过 maxDepth 时截断并标记 truncated</li>
 *   <li><b>批次不存在</b>：抛 NOT_FOUND 而不是 NPE</li>
 * </ol>
 */
@DisplayName("批次追溯（有向图 DAG）")
class TraceServiceTest {

    private final Map<String, ProductBatch> batchByNo = new HashMap<>();
    private final Map<Long, ProductBatch> batchById = new HashMap<>();
    private final List<BatchRelation> relations = new ArrayList<>();

    private TraceService traceService;

    @BeforeEach
    void setUp() {
        batchByNo.clear();
        batchById.clear();
        relations.clear();
    }

    /* ==================== 测试数据构造 ==================== */

    private ProductBatch addBatch(long id, String batchNo, int type, String code, String name,
                                  String qty, Long supplierId, String supplierName) {
        ProductBatch b = new ProductBatch();
        b.setId(id);
        b.setBatchNo(batchNo);
        b.setBatchType(type);
        if (type == ProductBatch.TYPE_MATERIAL) {
            b.setMaterialId(id);
            b.setMaterialCode(code);
            b.setMaterialName(name);
        } else {
            b.setProductId(id);
            b.setProductCode(code);
            b.setProductName(name);
        }
        b.setQuantity(new BigDecimal(qty));
        b.setSupplierId(supplierId);
        b.setSupplierName(supplierName);
        b.setStatus(1);
        batchByNo.put(batchNo, b);
        batchById.put(id, b);
        return b;
    }

    private void addRelation(long id, long sourceId, long targetId, String qty) {
        BatchRelation r = new BatchRelation();
        r.setId(id);
        r.setSourceBatchId(sourceId);
        r.setSourceBatchNo(batchById.get(sourceId).getBatchNo());
        r.setTargetBatchId(targetId);
        r.setTargetBatchNo(batchById.get(targetId).getBatchNo());
        r.setQuantity(new BigDecimal(qty));
        r.setRelationType("CONSUME");
        r.setIsReversed(0);
        relations.add(r);
    }

    /** 用当前数据装配服务 */
    private void buildService() {
        Recorder<ProductBatchMapper> batchMapper = Recorder.of(ProductBatchMapper.class,
                Stub.rule("selectByBatchNo", 1, inv -> batchByNo.get((String) inv.arg0())),
                Stub.rule("selectById", 1, inv -> batchById.get((Long) inv.arg0())));
        Recorder<BatchRelationMapper> relationMapper = Recorder.of(BatchRelationMapper.class,
                Stub.rule("selectAllActive", 0, inv -> new ArrayList<>(relations)));
        traceService = new TraceService(batchMapper.proxy(), relationMapper.proxy());
    }

    private List<TraceNode> flatten(List<TraceNode> nodes) {
        List<TraceNode> all = new ArrayList<>();
        if (nodes == null) {
            return all;
        }
        for (TraceNode n : nodes) {
            all.add(n);
            all.addAll(flatten(n.getChildren()));
        }
        return all;
    }

    /* ==================== 反向追溯 ==================== */

    @Test
    @DisplayName("反向追溯：成品 → 查出全部上游物料批次及供应商")
    void testBackwardTrace() {
        // 成品 PB001 用 2 个物料批次
        addBatch(1L, "PB001", ProductBatch.TYPE_PRODUCT, "PRD-X1", "智能电表", "100", null, null);
        addBatch(2L, "MB-Chip", ProductBatch.TYPE_MATERIAL, "MAT-MCU", "芯片", "1000", 1001L, "深圳华芯");
        addBatch(3L, "MB-Shell", ProductBatch.TYPE_MATERIAL, "MAT-SHELL", "表壳", "500", 1002L, "东莞精塑");
        addRelation(1L, 2L, 1L, "102");
        addRelation(2L, 3L, 1L, "101");
        buildService();

        TraceResultVO result = traceService.backward("PB001", 0);

        assertEquals("BACKWARD", result.getDirection());
        assertEquals("PB001", result.getRoot().getBatchNo());
        assertEquals(3, result.getTotalNodes(), "应涉及 3 个批次（1 产品 + 2 物料）");
        assertFalse(result.isTruncated());

        List<TraceNode> nodes = flatten(result.getTree());
        assertEquals(2, nodes.size(), "第一层应有 2 个上游物料批次");

        // 必须带出供应商（追溯的终点）
        TraceNode chip = nodes.stream().filter(n -> "MB-Chip".equals(n.getBatchNo()))
                .findFirst().orElse(null);
        assertNotNull(chip);
        assertEquals("深圳华芯", chip.getSupplierName(), "必须追溯到供应商");
        assertEquals(0, chip.getEdgeQuantity().compareTo(new BigDecimal("102")),
                "应带出消耗数量");
    }

    @Test
    @DisplayName("反向追溯：多层（3 层）能正确展开")
    void testBackwardMultiLevel() {
        // 产品 PB1 ← 半成品 SB1 ← 原料 MB1（3 层）
        addBatch(1L, "PB1", ProductBatch.TYPE_PRODUCT, "PRD-1", "成品", "10", null, null);
        addBatch(2L, "SB1", ProductBatch.TYPE_MATERIAL, "MAT-SEMI", "半成品", "20", null, null);
        addBatch(3L, "MB1", ProductBatch.TYPE_MATERIAL, "MAT-RAW", "原料", "100", 2001L, "供应商A");
        addRelation(1L, 2L, 1L, "10");
        addRelation(2L, 3L, 2L, "20");
        buildService();

        TraceResultVO result = traceService.backward("PB1", 0);

        assertEquals(3, result.getTotalNodes());
        assertEquals(2, result.getMaxDepth(), "应有 2 层上游（深度 2）");

        List<TraceNode> all = flatten(result.getTree());
        assertEquals(2, all.size());
        // 第二层应是 MB1
        TraceNode raw = all.stream().filter(n -> "MB1".equals(n.getBatchNo()))
                .findFirst().orElse(null);
        assertNotNull(raw, "应能追溯到最底层原料 MB1");
        assertEquals(2, raw.getLevel());
        assertEquals("供应商A", raw.getSupplierName());
    }

    @Test
    @DisplayName("反向追溯：无上游时返回空树，不报错")
    void testBackwardNoUpstream() {
        addBatch(1L, "PB1", ProductBatch.TYPE_PRODUCT, "PRD-1", "成品", "10", null, null);
        buildService();

        TraceResultVO result = traceService.backward("PB1", 0);
        assertEquals(1, result.getTotalNodes());
        assertTrue(result.getTree().isEmpty());
    }

    /* ==================== 正向追溯 ==================== */

    @Test
    @DisplayName("正向追溯：物料批次 → 查出影响的所有下游成品批次（精准召回）")
    void testForwardTrace() {
        // 同一芯片批次被 2 个工单消耗 → 2 个产品批次
        addBatch(1L, "MB-Chip", ProductBatch.TYPE_MATERIAL, "MAT-MCU", "芯片", "1000", 1001L, "深圳华芯");
        addBatch(2L, "PB001", ProductBatch.TYPE_PRODUCT, "PRD-X1", "电表", "100", null, null);
        addBatch(3L, "PB002", ProductBatch.TYPE_PRODUCT, "PRD-X1", "电表", "80", null, null);
        addRelation(1L, 1L, 2L, "102");
        addRelation(2L, 1L, 3L, "82");
        buildService();

        TraceResultVO result = traceService.forward("MB-Chip", 0);

        assertEquals("FORWARD", result.getDirection());
        assertEquals(3, result.getTotalNodes(), "应涉及 1 原料 + 2 成品");
        assertTrue(result.getMessage().contains("2"), "应提示影响 2 个下游批次");

        List<TraceNode> nodes = flatten(result.getTree());
        assertEquals(2, nodes.size());
        assertTrue(nodes.stream().anyMatch(n -> "PB001".equals(n.getBatchNo())));
        assertTrue(nodes.stream().anyMatch(n -> "PB002".equals(n.getBatchNo())));
    }

    @Test
    @DisplayName("正向追溯：多层（原料→半成品→成品）")
    void testForwardMultiLevel() {
        addBatch(1L, "MB1", ProductBatch.TYPE_MATERIAL, "MAT-RAW", "原料", "100", 1L, "A");
        addBatch(2L, "SB1", ProductBatch.TYPE_MATERIAL, "MAT-SEMI", "半成品", "20", null, null);
        addBatch(3L, "PB1", ProductBatch.TYPE_PRODUCT, "PRD-1", "成品", "10", null, null);
        addRelation(1L, 1L, 2L, "20");
        addRelation(2L, 2L, 3L, "10");
        buildService();

        TraceResultVO result = traceService.forward("MB1", 0);
        assertEquals(3, result.getTotalNodes());
        assertEquals(2, result.getMaxDepth());
    }

    /* ==================== 环路防护（关键） ==================== */

    @Test
    @DisplayName("环路防护：构造 A→B→A 环形数据不会死循环")
    void testCycleDetection() {
        // 人为构造环形：批次 1 → 2 → 1（业务上不该出现，但数据可能配错）
        addBatch(1L, "A", ProductBatch.TYPE_MATERIAL, "MAT-A", "A物料", "10", 1L, "S");
        addBatch(2L, "B", ProductBatch.TYPE_MATERIAL, "MAT-B", "B物料", "10", 2L, "S");
        addRelation(1L, 1L, 2L, "5");
        addRelation(2L, 2L, 1L, "5");   // ← 形成环
        buildService();

        // 关键断言：必须在有限时间内返回，不能死循环/栈溢出
        TraceResultVO forward = traceService.forward("A", 0);
        assertNotNull(forward, "有环数据也必须返回结果而不是卡死");
        assertTrue(forward.getTotalNodes() <= 2,
                "已访问节点不应重复展开，实际: " + forward.getTotalNodes());

        TraceResultVO backward = traceService.backward("A", 0);
        assertNotNull(backward, "反向遍历有环也必须安全返回");
        assertTrue(backward.getTotalNodes() <= 2);
    }

    @Test
    @DisplayName("环路防护：记录关系时拒绝自环")
    void testSelfRelationRejected() {
        addBatch(1L, "A", ProductBatch.TYPE_MATERIAL, "MAT-A", "A物料", "10", 1L, "S");
        buildService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> traceService.recordRelation("A", "A", 1L, "WO-1",
                        new BigDecimal("1"), "CONSUME"));
        assertTrue(ex.getMessage().contains("自环"),
                "应明确提示不能形成自环，实际: " + ex.getMessage());
    }

    /* ==================== 深度限制 ==================== */

    @Test
    @DisplayName("深度限制：maxDepth=1 时截断并标记 truncated")
    void testMaxDepthTruncation() {
        // 3 层链：MB1 ← MB2 ← MB3（反向追溯 3 层）
        addBatch(1L, "L1", ProductBatch.TYPE_PRODUCT, "PRD-1", "成品", "10", null, null);
        addBatch(2L, "L2", ProductBatch.TYPE_MATERIAL, "M-2", "料2", "20", null, null);
        addBatch(3L, "L3", ProductBatch.TYPE_MATERIAL, "M-3", "料3", "30", 3L, "S3");
        addRelation(1L, 2L, 1L, "10");
        addRelation(2L, 3L, 2L, "20");
        buildService();

        TraceResultVO deep = traceService.backward("L1", 10);
        assertFalse(deep.isTruncated(), "10 层限制下不应截断");
        assertEquals(2, deep.getMaxDepth());

        TraceResultVO shallow = traceService.backward("L1", 1);
        assertTrue(shallow.isTruncated(), "maxDepth=1 时应标记截断");
        assertEquals(1, shallow.getMaxDepth());
        assertTrue(shallow.getMessage().contains("深度上限"),
                "应提示已达深度上限，实际: " + shallow.getMessage());
        // 只展开 1 层：L2 出现，L3 不出现
        List<TraceNode> nodes = flatten(shallow.getTree());
        assertEquals(1, nodes.size());
        assertEquals("L2", nodes.get(0).getBatchNo());
    }

    /* ==================== 异常场景 ==================== */

    @Test
    @DisplayName("批次不存在 → 抛 NOT_FOUND，不是 NPE")
    void testBatchNotFound() {
        buildService();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> traceService.backward("NOT-EXIST", 0));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());

        assertThrows(BusinessException.class, () -> traceService.forward("NOT-EXIST", 0));
    }

    /* ==================== 记录关系 ==================== */

    @Test
    @DisplayName("记录批次关系：源批次必须存在")
    void testRecordRelationSourceNotFound() {
        buildService();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> traceService.recordRelation("NOT-EXIST", null, 1L, "WO-1",
                        new BigDecimal("1"), "CONSUME"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("记录批次关系：领料阶段目标批次可为空（待完工回填）")
    void testRecordRelationWithNullTarget() {
        addBatch(1L, "A", ProductBatch.TYPE_MATERIAL, "MAT-A", "A物料", "10", 1L, "S");
        Recorder<ProductBatchMapper> bm = Recorder.of(ProductBatchMapper.class,
                Stub.rule("selectByBatchNo", 1, inv -> batchByNo.get((String) inv.arg0())),
                Stub.rule("selectById", 1, inv -> batchById.get((Long) inv.arg0())));
        List<BatchRelation> inserted = new ArrayList<>();
        Recorder<BatchRelationMapper> rm = Recorder.of(BatchRelationMapper.class,
                Stub.rule("selectAllActive", 0, inv -> new ArrayList<>()),
                Stub.rule("insert", 1, inv -> {
                    inserted.add((BatchRelation) inv.arg0());
                    return 1;
                }));
        traceService = new TraceService(bm.proxy(), rm.proxy());

        BatchRelation rel = traceService.recordRelation("A", null, 100L, "WO-100",
                new BigDecimal("5"), "CONSUME");

        assertNotNull(rel);
        assertEquals(1, inserted.size());
        // 目标批次为空 → 等完工后由 bindOrderConsumptionsToProductBatch 回填
        assertEquals(null, inserted.get(0).getTargetBatchId());
        assertEquals(0, inserted.get(0).getIsReversed().intValue(),
                "新记录不应标记为已冲正");
        assertEquals("CONSUME", inserted.get(0).getRelationType());
    }
}
