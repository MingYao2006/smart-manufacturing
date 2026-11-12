package com.smart.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.inventory.mapper.MaterialIssueOutboxMapper;
import com.smart.inventory.support.Stub;
import com.smart.inventory.support.Stub.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领料 outbox 单测：验证"同事务写、幂等键、对账补录"三件事。
 *
 * <p>用 JDK 动态代理桩替换 Mapper，不启动数据库（原因见 Stub 类注释）。
 */
@DisplayName("领料批次关系 outbox")
class MaterialIssueOutboxServiceTest {

    private Recorder<MaterialIssueOutboxMapper> mapper;
    private MaterialIssueOutboxService service;

    @BeforeEach
    void setUp() {
        mapper = Recorder.of(MaterialIssueOutboxMapper.class);
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());
    }

    @Test
    @DisplayName("领料带批次号 → 同事务写一条待发消息，target 不传（完工才回填）")
    void testEnqueueWritesPendingMessageWithoutTarget() {
        AtomicInteger ignored = new AtomicInteger();
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("insertIgnore", 1, Stub.returnsInt(1)));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        service.enqueue(100L, "WO-100", "IS-1", 1L, "MAT-A", "MB-1", new BigDecimal("30"));

        assertEquals(1, mapper.callCount("insertIgnore"));
        Object[] args = mapper.firstArgs("insertIgnore");
        var outbox = (com.smart.inventory.entity.MaterialIssueOutbox) args[0];
        assertEquals("100:MB-1", outbox.getBizKey(), "幂等键 = 工单号 + 批次号");
        assertEquals(100L, outbox.getOrderId());
        assertEquals("MB-1", outbox.getBatchNo());
        String payload = outbox.getPayload();
        assertTrue(payload.contains("\"sourceBatchNo\":\"MB-1\""));
        assertTrue(payload.contains("\"productionOrderId\":100"));
        assertTrue(!payload.contains("targetBatchNo"),
                "领料那刻产品批次还没生成，target 为空是设计，不是漏写");
    }

    @Test
    @DisplayName("重复投递 / 重复领料 → INSERT IGNORE 只落一条（幂等）")
    void testEnqueueIdempotent() {
        // 第二次 insertIgnore 返回 0 = 唯一索引命中，跳过，不建第二条边
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("insertIgnore", 1, Stub.returnsInt(0)));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        service.enqueue(100L, "WO-100", "IS-1", 1L, "MAT-A", "MB-1", new BigDecimal("30"));

        assertEquals(1, mapper.callCount("insertIgnore"), "仍只尝试一次写入");
    }

    @Test
    @DisplayName("非批次管理物料（无批次号）→ 跳过，不产生待发消息")
    void testEnqueueSkipsWhenNoBatchNo() {
        service.enqueue(100L, "WO-100", "IS-1", 1L, "MAT-A", null, new BigDecimal("30"));
        assertEquals(0, mapper.callCount("insertIgnore"));
    }

    @Test
    @DisplayName("outbox 写入失败 → 异常上抛，让领料事务整体回滚（不能料发了本子没写）")
    void testEnqueueFailurePropagatesToRollbackIssue() {
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("insertIgnore", 1, inv -> {
                    throw new IllegalStateException("模拟数据库写入失败");
                }));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        // 异常必须穿透 enqueue：调用方 IssueService.issue 的 @Transactional 才能回滚扣减
        assertTrue(assertThrows(IllegalStateException.class,
                () -> service.enqueue(100L, "WO-100", "IS-1", 1L, "MAT-A", "MB-1",
                        new BigDecimal("30"))).getMessage().contains("模拟数据库写入失败"));
    }

    @Test
    @DisplayName("对账补录：流水里有批次号但 outbox 没有 → 补录并告警")
    void testRepairMissingRelations() {
        List<Map<String, Object>> missing = new ArrayList<>();
        missing.add(Map.of("tx_id", 9L, "issue_no", "IS-9", "material_id", 1L,
                "material_code", "MAT-A", "batch_no", "MB-9", "quantity", new BigDecimal("5"),
                "order_id", 100L, "order_no", "WO-100"));
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("selectMissingRelations", 1, inv -> missing),
                Stub.rule("insertIgnore", 1, Stub.returnsInt(1)));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        service.repairMissingRelations();

        assertEquals(1, mapper.callCount("selectMissingRelations"));
        assertEquals(1, mapper.callCount("insertIgnore"), "漏写的消息必须补录");
        var outbox = (com.smart.inventory.entity.MaterialIssueOutbox)
                mapper.firstArgs("insertIgnore")[0];
        assertEquals("100:MB-9", outbox.getBizKey());
        assertEquals("IS-9", outbox.getIssueNo());
    }

    @Test
    @DisplayName("对账：没有漏写则不做任何补录")
    void testRepairNoopWhenNothingMissing() {
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("selectMissingRelations", 1, inv -> new ArrayList<Map<String, Object>>()));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        service.repairMissingRelations();

        assertEquals(0, mapper.callCount("insertIgnore"));
    }

    @Test
    @DisplayName("对账：单行补录失败不影响其余行（对账不在领料事务里，逐行兜异常）")
    void testRepairContinuesAfterRowFailure() {
        List<Map<String, Object>> missing = new ArrayList<>();
        missing.add(Map.of("tx_id", 1L, "issue_no", "IS-1", "material_id", 1L,
                "material_code", "MAT-A", "batch_no", "MB-BAD", "quantity", new BigDecimal("5"),
                "order_id", 100L, "order_no", "WO-100"));
        missing.add(Map.of("tx_id", 2L, "issue_no", "IS-2", "material_id", 1L,
                "material_code", "MAT-A", "batch_no", "MB-OK", "quantity", new BigDecimal("6"),
                "order_id", 100L, "order_no", "WO-100"));
        AtomicInteger attempts = new AtomicInteger();
        mapper = Recorder.of(MaterialIssueOutboxMapper.class,
                Stub.rule("selectMissingRelations", 1, inv -> missing),
                Stub.rule("insertIgnore", 1, inv -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("模拟第一行写入失败");
                    }
                    return 1;
                }));
        service = new MaterialIssueOutboxService(mapper.proxy(), new ObjectMapper());

        service.repairMissingRelations();

        assertEquals(2, mapper.callCount("insertIgnore"), "第一行失败后第二行仍要补录");
    }
}
