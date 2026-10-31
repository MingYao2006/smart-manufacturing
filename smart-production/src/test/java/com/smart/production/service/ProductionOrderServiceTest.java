package com.smart.production.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.production.dto.ProductionDtos.OrderCreateDTO;
import com.smart.production.dto.ProductionDtos.OrderVO;
import com.smart.production.entity.ProductionOrder;
import com.smart.production.mapper.OrderStatusLogMapper;
import com.smart.production.mapper.ProductionOrderMapper;
import com.smart.production.mapper.ProductionPlanMapper;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.statemachine.ProductionOrderStatus;
import com.smart.production.support.FakeProductCatalog;
import com.smart.production.support.Stub;
import com.smart.production.support.Stub.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单服务单元测试（第 3 周任务 3.1 / 3.2 / 3.4）。
 *
 * <h3>核心验证点</h3>
 * <ol>
 *   <li><b>快照</b>：创建工单必须写入 bomId / routeId（制造业铁律）</li>
 *   <li><b>前置校验</b>：产品停用、无生效 BOM、无生效工艺路线都必须拒绝创建</li>
 *   <li><b>CAS 并发</b>：影响行数 0 → 抛 CONCURRENT_CONFLICT（并发状态覆盖防线）</li>
 *   <li><b>非法迁移</b>：DRAFT 不能直接 RELEASED</li>
 *   <li><b>状态日志</b>：每次流转必须留痕</li>
 * </ol>
 */
@DisplayName("工单服务：创建与状态流转")
class ProductionOrderServiceTest {

    private Recorder<ProductionOrderMapper> orderMapper;
    private Recorder<ProductionPlanMapper> planMapper;
    private Recorder<WorkTaskMapper> workTaskMapper;
    private Recorder<OrderStatusLogMapper> statusLogMapper;
    private FakeProductCatalog productCatalog;

    private ProductionOrderService orderService;

    @BeforeEach
    void setUp() {
        productCatalog = FakeProductCatalog.healthy();

        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("insert", 1, inv -> {
                    ((ProductionOrder) inv.arg0()).setId(1L);
                    return 1;
                }),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)),
                Stub.rule("markActualStart", 1, Stub.returnsInt(1)),
                Stub.rule("markActualEnd", 1, Stub.returnsInt(1)),
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.DRAFT.getCode())));
        planMapper = Recorder.of(ProductionPlanMapper.class);
        workTaskMapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(0)));
        statusLogMapper = Recorder.of(OrderStatusLogMapper.class,
                Stub.rule("insert", 1, Stub.returnsInt(1)));

        WorkTaskService workTaskService = new WorkTaskService(workTaskMapper.proxy(), productCatalog);
        orderService = new ProductionOrderService(orderMapper.proxy(), planMapper.proxy(),
                workTaskMapper.proxy(), statusLogMapper.proxy(), productCatalog, workTaskService);
    }

    private ProductionOrder buildOrder(Long id, int status) {
        ProductionOrder o = new ProductionOrder();
        o.setId(id);
        o.setOrderNo("WO-TEST-001");
        o.setProductId(1L);
        o.setProductCode("PRD-DB-X1");
        o.setProductName("智能电表 X1");
        o.setBomId(1L);
        o.setBomVersion("V1");
        o.setRouteId(1L);
        o.setRouteVersion("V1");
        o.setPlanQuantity(new BigDecimal("100"));
        o.setCompletedQuantity(BigDecimal.ZERO);
        o.setQualifiedQuantity(BigDecimal.ZERO);
        o.setDefectQuantity(BigDecimal.ZERO);
        o.setProgress(0);
        o.setStatus(status);
        return o;
    }

    private OrderCreateDTO buildCreateDto() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setProductId(1L);
        dto.setPlanQuantity(new BigDecimal("100"));
        return dto;
    }

    /* ==================== 创建工单：快照 ==================== */

    @Test
    @DisplayName("创建工单：必须快照 bomId 与 routeId（制造业铁律）")
    void testCreateOrderSnapshotsBomAndRoute() {
        OrderVO vo = orderService.createOrder(buildCreateDto());

        assertNotNull(vo);
        assertEquals(1L, vo.getBomId(), "必须写入锁定的 BOM 版本 ID");
        assertEquals("V1", vo.getBomVersion(), "必须写入 BOM 版本号快照");
        assertEquals(1L, vo.getRouteId(), "必须写入锁定的工艺路线版本 ID");
        assertEquals("V1", vo.getRouteVersion(), "必须写入工艺路线版本号快照");

        // 初始状态必须是草稿
        assertEquals(ProductionOrderStatus.DRAFT.getCode(), vo.getStatus());
        assertEquals("草稿", vo.getStatusDesc());

        // 必须写库并留状态日志
        assertEquals(1, orderMapper.callCount("insert"));
        assertEquals(1, statusLogMapper.callCount("insert"), "创建工单必须留状态日志");
    }

    @Test
    @DisplayName("工单号格式：WO + 日期 + 序列")
    void testOrderNoFormat() {
        OrderVO vo = orderService.createOrder(buildCreateDto());
        assertNotNull(vo.getOrderNo());
        assertTrue(vo.getOrderNo().startsWith("WO"),
                "工单号应以 WO 开头，实际: " + vo.getOrderNo());
        assertTrue(vo.getOrderNo().length() >= 12,
                "工单号应包含日期+序列，实际: " + vo.getOrderNo());
    }

    /* ==================== 创建工单：前置校验 ==================== */

    @Test
    @DisplayName("产品停用 → 拒绝创建工单")
    void testCreateOrderProductDisabled() {
        productCatalog.withDisabledProduct();
        WorkTaskService wts = new WorkTaskService(workTaskMapper.proxy(), productCatalog);
        orderService = new ProductionOrderService(orderMapper.proxy(), planMapper.proxy(),
                workTaskMapper.proxy(), statusLogMapper.proxy(), productCatalog, wts);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(buildCreateDto()));
        assertTrue(ex.getMessage().contains("停用"), "提示应说明产品已停用，实际: " + ex.getMessage());
        assertEquals(0, orderMapper.callCount("insert"), "校验失败不应插入工单");
    }

    @Test
    @DisplayName("产品无生效 BOM → 拒绝创建（BOM_NOT_EFFECTIVE 40908）")
    void testCreateOrderNoActiveBom() {
        productCatalog.withoutBom();
        WorkTaskService wts = new WorkTaskService(workTaskMapper.proxy(), productCatalog);
        orderService = new ProductionOrderService(orderMapper.proxy(), planMapper.proxy(),
                workTaskMapper.proxy(), statusLogMapper.proxy(), productCatalog, wts);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(buildCreateDto()));
        assertEquals(ErrorCode.BOM_NOT_EFFECTIVE.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("BOM"));
        assertEquals(0, orderMapper.callCount("insert"));
    }

    @Test
    @DisplayName("产品无生效工艺路线 → 拒绝创建（ROUTE_NOT_EFFECTIVE 40909）")
    void testCreateOrderNoActiveRoute() {
        productCatalog.withoutRoute();
        WorkTaskService wts = new WorkTaskService(workTaskMapper.proxy(), productCatalog);
        orderService = new ProductionOrderService(orderMapper.proxy(), planMapper.proxy(),
                workTaskMapper.proxy(), statusLogMapper.proxy(), productCatalog, wts);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(buildCreateDto()));
        assertEquals(ErrorCode.ROUTE_NOT_EFFECTIVE.getCode(), ex.getCode());
        assertEquals(0, orderMapper.callCount("insert"));
    }

    @Test
    @DisplayName("计划数量 <= 0 → 拒绝创建")
    void testCreateOrderInvalidQuantity() {
        OrderCreateDTO dto = buildCreateDto();
        dto.setPlanQuantity(BigDecimal.ZERO);
        assertThrows(BusinessException.class, () -> orderService.createOrder(dto));

        dto.setPlanQuantity(new BigDecimal("-10"));
        assertThrows(BusinessException.class, () -> orderService.createOrder(dto));
    }

    /* ==================== 状态流转：非法迁移 ==================== */

    @Test
    @DisplayName("非法迁移：草稿直接下达 → 拒绝（必须先审核）")
    void testIllegalTransitDraftToReleased() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.DRAFT.getCode())),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));
        rebuildService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.transit(1L, ProductionOrderStatus.RELEASED, "测试跳过审核"));

        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), ex.getCode());
        assertEquals(0, orderMapper.callCount("updateStatusCas"),
                "非法迁移不应执行数据库更新");
    }

    /**
     * 用当前的 orderMapper 重新装配服务（保持其它依赖不变）。
     */
    private void rebuildService() {
        WorkTaskService wts = new WorkTaskService(workTaskMapper.proxy(), productCatalog);
        orderService = new ProductionOrderService(orderMapper.proxy(), planMapper.proxy(),
                workTaskMapper.proxy(), statusLogMapper.proxy(), productCatalog, wts);
    }

    /* ==================== 状态流转：CAS 并发 ==================== */

    @Test
    @DisplayName("CAS 并发：影响行数 0 → 抛 CONCURRENT_CONFLICT(40902)")
    void testTransitCasConflict() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.DRAFT.getCode())),
                // 模拟并发：状态已被别的线程改过，CAS 影响行数 0
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(0)));
        rebuildService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.transit(1L, ProductionOrderStatus.APPROVED, "审核"));

        assertEquals(ErrorCode.CONCURRENT_CONFLICT.getCode(), ex.getCode(),
                "CAS 失败应返回并发冲突 40902");
        assertTrue(ex.getMessage().contains("已变更"),
                "提示应说明状态已变更，实际: " + ex.getMessage());
        assertEquals(0, statusLogMapper.callCount("insert"),
                "CAS 失败不应写状态日志（否则日志与实际状态不符）");
    }

    @Test
    @DisplayName("CAS 成功：影响行数 1 → 写状态日志")
    void testTransitSuccessWritesLog() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.DRAFT.getCode())),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));
        rebuildService();

        OrderVO vo = orderService.transit(1L, ProductionOrderStatus.APPROVED, "审核通过");

        assertNotNull(vo);
        assertEquals(1, orderMapper.callCount("updateStatusCas"));
        // CAS 参数：target=APPROVED(2), expected=DRAFT(1)
        Object[] args = orderMapper.firstArgs("updateStatusCas");
        assertEquals(ProductionOrderStatus.APPROVED.getCode(), args[1]);
        assertEquals(ProductionOrderStatus.DRAFT.getCode(), args[2]);

        assertEquals(1, statusLogMapper.callCount("insert"), "流转成功必须写状态日志");
    }

    /* ==================== 状态流转：副作用 ==================== */

    @Test
    @DisplayName("下达工单：生成工序任务（同步，幂等）")
    void testReleaseGeneratesWorkTasks() {
        // 工单当前为 APPROVED，下达时按快照 routeId 展开 3 道工序
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.APPROVED.getCode())),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));

        // 工序任务未生成过（count=0），插入成功
        AtomicInteger inserted = new AtomicInteger();
        workTaskMapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(0)),
                Stub.rule("insert", 1, inv -> inserted.incrementAndGet()));
        rebuildService();

        orderService.release(1L, "下达");

        // Fake 工艺路线有 3 道工序 → 应生成 3 条任务
        assertEquals(3, inserted.get(), "下达应生成 3 道工序任务");
        assertEquals(3, workTaskMapper.callCount("insert"));
    }

    @Test
    @DisplayName("重复下达：已有工序任务时不重复生成（幂等）")
    void testReleaseIdempotentTaskGeneration() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.APPROVED.getCode())),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));
        // 已有 3 条任务
        workTaskMapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(3)),
                Stub.rule("insert", 1, Stub.returnsInt(1)));
        rebuildService();

        orderService.release(1L, "重复下达");

        assertEquals(0, workTaskMapper.callCount("insert"),
                "已有工序任务时不应重复插入（uk_order_step 兜底）");
    }

    /* ==================== 便捷方法 ==================== */

    @Test
    @DisplayName("approve() 走统一的 transit 入口")
    void testApproveUsesTransit() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> buildOrder(1L,
                        ProductionOrderStatus.DRAFT.getCode())),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));
        rebuildService();

        orderService.approve(1L, null);

        assertEquals(1, orderMapper.callCount("updateStatusCas"));
        Object[] args = orderMapper.firstArgs("updateStatusCas");
        assertEquals(ProductionOrderStatus.APPROVED.getCode(), args[1]);
    }

    @Test
    @DisplayName("工单不存在 → 抛 NOT_FOUND")
    void testOrderNotFound() {
        orderMapper = Recorder.of(ProductionOrderMapper.class,
                Stub.rule("selectById", 1, inv -> null));
        rebuildService();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.transit(999L, ProductionOrderStatus.APPROVED, "审核"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }
}
