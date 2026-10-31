package com.smart.production.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.production.dto.ProductionDtos.ReportCreateDTO;
import com.smart.production.dto.ProductionDtos.ReportResultVO;
import com.smart.production.entity.ProductionReport;
import com.smart.production.entity.WorkTask;
import com.smart.production.mapper.ProductionReportMapper;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.statemachine.WorkTaskStatus;
import com.smart.production.support.FakeMessagePublisher;
import com.smart.production.support.FakeProductCatalog;
import com.smart.production.support.Stub;
import com.smart.production.support.Stub.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产报工服务单元测试（第 5 周任务 5.3 / 5.4 / 第 7 周任务 7.1）。
 *
 * <h3>测试替身策略</h3>
 * <ul>
 *   <li>Mapper：{@link Stub}（JDK 动态代理，替代 Mockito，原因见 Stub 注释）</li>
 *   <li>外部依赖：{@link FakeProductCatalog} / {@link FakeMessagePublisher}
 *       —— 计划书要求的"外部依赖抽成接口，测试时用 Fake"</li>
 * </ul>
 *
 * <h3>这几个是本项目最容易做错的地方，也正是测试重点</h3>
 * <ol>
 *   <li><b>合格数 + 不良数 = 报工数</b>：必须服务端校验，前端不可信</li>
 *   <li><b>超报</b>：条件更新影响行数为 0 → 抛 QUANTITY_EXCEED_PLAN</li>
 *   <li><b>幂等</b>：同一报工单号重复提交只生效一次</li>
 *   <li><b>状态门禁</b>：只有 IN_PROGRESS 的工序可以报工</li>
 *   <li><b>outbox</b>：报工成功必须在同一事务写本地消息表</li>
 * </ol>
 */
@DisplayName("生产报工服务")
class ProductionReportServiceTest {

    private static final long TASK_ID = 1L;
    private static final long ORDER_ID = 100L;
    private static final String ORDER_NO = "WO-TEST-001";

    private WorkTask task;
    private FakeMessagePublisher messagePublisher;

    @BeforeEach
    void setUp() {
        task = new WorkTask();
        task.setId(TASK_ID);
        task.setOrderId(ORDER_ID);
        task.setOrderNo(ORDER_NO);
        task.setProductId(1L);
        task.setStepNo(10);
        task.setStepName("外壳组装");
        task.setPlanQuantity(new BigDecimal("100"));
        task.setReportedQuantity(BigDecimal.ZERO);
        task.setQualifiedQuantity(BigDecimal.ZERO);
        task.setDefectQuantity(BigDecimal.ZERO);
        task.setActualHours(BigDecimal.ZERO);
        task.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        task.setIsInspection(0);

        messagePublisher = new FakeMessagePublisher();
    }

    /**
     * 构造被测服务。
     *
     * @param addResult          条件更新影响行数（1=成功，0=超报）
     * @param existingReportNo   已存在的同单号报工数（幂等）
     * @param taskAfterAdd       条件更新后重新读到的任务（模拟累加结果）
     * @param currentTask        首次读取到的任务
     */
    private Recorder<WorkTaskMapper> workTaskMapper;
    private Recorder<ProductionReportMapper> reportMapper;

    private void buildService(int addResult, int existingReportNo,
                              WorkTask currentTask, WorkTask taskAfterAdd) {
        workTaskMapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> currentTask),
                Stub.rule("addReportedQuantity", 6, Stub.returnsInt(addResult)),
                Stub.rule("finishTask", 4, Stub.returnsInt(1)));
        reportMapper = Recorder.of(ProductionReportMapper.class,
                Stub.rule("countByReportNo", 1, Stub.returnsInt(existingReportNo)),
                Stub.rule("selectOne", 1, inv -> null),
                Stub.rule("insert", 1, inv -> {
                    ((ProductionReport) inv.arg0()).setId(500L);
                    return 1;
                }));

        // WorkTaskService 用同一份 mapper 桩（它需要 selectById 拿到"累加后"的任务）
        Recorder<WorkTaskMapper> forTaskService = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> taskAfterAdd),
                Stub.rule("selectPreviousStep", 2, inv -> null),
                Stub.rule("finishTask", 4, Stub.returnsInt(1)),
                Stub.rule("updateStatusCas", 4, Stub.returnsInt(1)));
        WorkTaskService workTaskService = new WorkTaskService(
                forTaskService.proxy(), FakeProductCatalog.empty());

        reportService = new ProductionReportService(workTaskMapper.proxy(),
                reportMapper.proxy(), workTaskService, messagePublisher,
                FakeProductCatalog.empty());
    }

    private ProductionReportService reportService;

    private ReportCreateDTO buildReport(String reportNo, String reportQty,
                                        String qualifiedQty, String defectQty) {
        ReportCreateDTO dto = new ReportCreateDTO();
        dto.setReportNo(reportNo);
        dto.setTaskId(TASK_ID);
        dto.setReportQuantity(new BigDecimal(reportQty));
        dto.setQualifiedQuantity(new BigDecimal(qualifiedQty));
        dto.setDefectQuantity(new BigDecimal(defectQty));
        dto.setActualHours(new BigDecimal("2"));
        dto.setOperatorId(4L);
        dto.setOperatorName("王工");
        return dto;
    }

    private WorkTask taskAfter(String reported, String qualified, String defect, int status) {
        WorkTask t = new WorkTask();
        t.setId(TASK_ID);
        t.setOrderId(ORDER_ID);
        t.setOrderNo(ORDER_NO);
        t.setProductId(1L);
        t.setStepNo(10);
        t.setStepName("外壳组装");
        t.setPlanQuantity(new BigDecimal("100"));
        t.setReportedQuantity(new BigDecimal(reported));
        t.setQualifiedQuantity(new BigDecimal(qualified));
        t.setDefectQuantity(new BigDecimal(defect));
        t.setStatus(status);
        t.setIsInspection(0);
        return t;
    }

    /* ==================== 数量校验 ==================== */

    @Test
    @DisplayName("合格数 + 不良数 ≠ 报工数 → 必须拒绝（服务端校验）")
    void testQualifiedPlusDefectMismatch() {
        buildService(1, 0, task, task);

        // 15 + 3 = 18 ≠ 20
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-001", "20", "15", "3")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("必须等于"),
                "提示应说明数量不匹配，实际: " + ex.getMessage());

        // 校验失败绝不能累加或写记录
        assertEquals(0, workTaskMapper.callCount("addReportedQuantity"),
                "数量校验失败时不应累加报工量");
        assertEquals(0, reportMapper.callCount("insert"));
        assertTrue(messagePublisher.isEmpty(), "校验失败不应写 outbox");
    }

    @Test
    @DisplayName("报工数量为 0 或负数 → 拒绝")
    void testNonPositiveQuantity() {
        buildService(1, 0, task, task);

        assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-002", "0", "0", "0")));
        assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-003", "-5", "-5", "0")));

        assertEquals(0, workTaskMapper.callCount("addReportedQuantity"));
    }

    /* ==================== 超报 ==================== */

    @Test
    @DisplayName("超报：条件更新影响 0 行 → 抛 QUANTITY_EXCEED_PLAN(40905)")
    void testReportExceedPlan() {
        // 已报 90，本次 20 → 110 > 100，条件更新返回 0
        WorkTask latest = taskAfter("90", "90", "0", WorkTaskStatus.IN_PROGRESS.getCode());
        buildService(0, 0, latest, latest);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-004", "20", "20", "0")));

        assertEquals(ErrorCode.QUANTITY_EXCEED_PLAN.getCode(), ex.getCode(),
                "应返回超报错误码 40905");
        assertTrue(ex.getMessage().contains("超出计划数量"),
                "提示应说明超报，实际: " + ex.getMessage());

        // 超报时不应写报工明细与 outbox
        assertEquals(0, reportMapper.callCount("insert"), "超报时不应写报工明细");
        assertTrue(messagePublisher.isEmpty(), "超报时不应写 outbox");
    }

    /* ==================== 正常报工 ==================== */

    @Test
    @DisplayName("正常报工：累加成功 → 写明细 + 写 outbox（同事务）")
    void testReportSuccess() {
        WorkTask afterAdd = taskAfter("20", "19", "1", WorkTaskStatus.IN_PROGRESS.getCode());
        afterAdd.setIsInspection(1);   // 质检点
        buildService(1, 0, task, afterAdd);

        ReportResultVO vo = reportService.report(buildReport("RP-005", "20", "19", "1"));

        assertNotNull(vo);
        assertEquals("RP-005", vo.getReportNo());
        assertEquals(TASK_ID, vo.getTaskId());

        // 条件更新被调用（并发安全防线）
        assertEquals(1, workTaskMapper.callCount("addReportedQuantity"));
        // 报工明细被写入
        assertEquals(1, reportMapper.callCount("insert"));
        Object savedReport = reportMapper.firstArgs("insert")[0];
        assertTrue(savedReport instanceof ProductionReport);
        assertEquals(0, ((ProductionReport) savedReport).getReportQuantity()
                .compareTo(new BigDecimal("20")));

        // 🔑 outbox 必须被写入（消息可靠性）
        assertEquals(1, messagePublisher.count(),
                "报工成功必须在同事务写 outbox，否则 MQ 宕机会丢消息");
        FakeMessagePublisher.Published pub = messagePublisher.first();
        assertTrue(pub.bizKey().startsWith("report:"),
                "bizKey 应以 report: 开头，实际: " + pub.bizKey());
        assertTrue(pub.bizKey().endsWith(":order-progress"),
                "bizKey 应表达业务含义，实际: " + pub.bizKey());
        assertEquals("production.exchange", pub.exchangeName());
        assertEquals("report.completed", pub.routingKey());
        assertNotNull(pub.payload(), "消息体不能为空");
    }

    /* ==================== 幂等 ==================== */

    @Test
    @DisplayName("幂等：同一报工单号重复提交 → 直接返回，不重复累加")
    void testReportIdempotent() {
        ProductionReport existing = new ProductionReport();
        existing.setId(600L);
        existing.setReportNo("RP-006");
        existing.setTaskId(TASK_ID);
        existing.setReportQuantity(new BigDecimal("20"));

        workTaskMapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> taskAfter("20", "20", "0",
                        WorkTaskStatus.IN_PROGRESS.getCode())));
        reportMapper = Recorder.of(ProductionReportMapper.class,
                Stub.rule("countByReportNo", 1, Stub.returnsInt(1)),   // 已存在
                Stub.rule("selectOne", 1, inv -> existing));
        Recorder<WorkTaskMapper> forTaskService = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> task));
        WorkTaskService workTaskService = new WorkTaskService(
                forTaskService.proxy(), FakeProductCatalog.empty());
        reportService = new ProductionReportService(workTaskMapper.proxy(),
                reportMapper.proxy(), workTaskService, messagePublisher,
                FakeProductCatalog.empty());

        ReportResultVO vo = reportService.report(buildReport("RP-006", "20", "20", "0"));

        assertTrue(vo.getMessage().contains("幂等"),
                "应提示幂等返回，实际: " + vo.getMessage());

        // 关键断言：绝不能重复累加、不能重复写 outbox
        assertEquals(0, workTaskMapper.callCount("addReportedQuantity"),
                "幂等返回时不应累加报工量");
        assertTrue(messagePublisher.isEmpty(), "幂等返回时不应重复写 outbox");
    }

    /* ==================== 状态门禁 ==================== */

    @Test
    @DisplayName("工序未开始（PENDING）时不允许报工")
    void testReportOnPendingTask() {
        WorkTask pending = taskAfter("0", "0", "0", WorkTaskStatus.PENDING.getCode());
        buildService(1, 0, pending, pending);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-007", "10", "10", "0")));

        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("进行中"),
                "提示应说明只有进行中的工序可报工，实际: " + ex.getMessage());
    }

    @Test
    @DisplayName("工序已完成（COMPLETED）时不允许再报工")
    void testReportOnCompletedTask() {
        WorkTask completed = taskAfter("100", "100", "0", WorkTaskStatus.COMPLETED.getCode());
        buildService(1, 0, completed, completed);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reportService.report(buildReport("RP-008", "10", "10", "0")));
        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), ex.getCode());
    }

    /* ==================== 报满后流转 ==================== */

    @Test
    @DisplayName("报满且是质检点 → 工序应流转到 WAIT_INSPECTION")
    void testAfterReportFullWithInspection() {
        WorkTask full = new WorkTask();
        full.setId(TASK_ID);
        full.setOrderId(ORDER_ID);
        full.setOrderNo(ORDER_NO);
        full.setStepNo(20);
        full.setStepName("老化测试");
        full.setPlanQuantity(new BigDecimal("100"));
        full.setReportedQuantity(new BigDecimal("100"));
        full.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        full.setIsInspection(1);

        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> full),
                Stub.rule("finishTask", 4, Stub.returnsInt(1)));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        WorkTaskStatus after = svc.afterReport(TASK_ID, 4L);

        assertEquals(WorkTaskStatus.WAIT_INSPECTION, after,
                "质检点报满后必须进 WAIT_INSPECTION 而不是直接 COMPLETED");
        assertEquals(1, mapper.callCount("finishTask"));
        Object[] args = mapper.firstArgs("finishTask");
        assertEquals(WorkTaskStatus.WAIT_INSPECTION.getCode(), args[1]);
    }

    @Test
    @DisplayName("报满但不是质检点 → 工序应流转到 COMPLETED")
    void testAfterReportFullWithoutInspection() {
        WorkTask full = new WorkTask();
        full.setId(TASK_ID);
        full.setOrderId(ORDER_ID);
        full.setOrderNo(ORDER_NO);
        full.setStepNo(10);
        full.setPlanQuantity(new BigDecimal("100"));
        full.setReportedQuantity(new BigDecimal("100"));
        full.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        full.setIsInspection(0);

        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> full),
                Stub.rule("finishTask", 4, Stub.returnsInt(1)));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        WorkTaskStatus after = svc.afterReport(TASK_ID, 4L);
        assertEquals(WorkTaskStatus.COMPLETED, after);

        Object[] args = mapper.firstArgs("finishTask");
        assertEquals(WorkTaskStatus.COMPLETED.getCode(), args[1]);
    }

    @Test
    @DisplayName("未报满 → 工序保持 IN_PROGRESS，不流转")
    void testAfterReportNotFull() {
        WorkTask partial = new WorkTask();
        partial.setId(TASK_ID);
        partial.setPlanQuantity(new BigDecimal("100"));
        partial.setReportedQuantity(new BigDecimal("40"));
        partial.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        partial.setIsInspection(0);

        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> partial));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        WorkTaskStatus after = svc.afterReport(TASK_ID, 4L);
        assertEquals(WorkTaskStatus.IN_PROGRESS, after);
        assertEquals(0, mapper.callCount("finishTask"), "未报满不应流转状态");
    }

    /* ==================== 工序顺序约束 ==================== */

    @Test
    @DisplayName("前道工序未完成 → 拒绝开始本工序（工艺纪律）")
    void testSequenceConstraint() {
        WorkTask current = new WorkTask();
        current.setId(2L);
        current.setOrderId(ORDER_ID);
        current.setStepNo(20);
        current.setStepName("老化测试");
        current.setStatus(WorkTaskStatus.PENDING.getCode());

        WorkTask prev = new WorkTask();
        prev.setId(1L);
        prev.setOrderId(ORDER_ID);
        prev.setStepNo(10);
        prev.setStepName("外壳组装");
        prev.setStatus(WorkTaskStatus.PENDING.getCode());  // 未完成
        prev.setIsInspection(0);

        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> current),
                Stub.rule("selectPreviousStep", 2, inv -> prev));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.startTask(2L, 4L));

        assertEquals(ErrorCode.PROCESS_SEQUENCE_VIOLATION.getCode(), ex.getCode(),
                "应返回工序顺序违规 40906");
        assertTrue(ex.getMessage().contains("未完成"),
                "提示应说明前道未完成，实际: " + ex.getMessage());
        assertEquals(0, mapper.callCount("startTask"), "校验失败不应更新状态");
    }

    @Test
    @DisplayName("前道工序已完成 → 允许开始本工序")
    void testSequenceConstraintPassed() {
        WorkTask current = new WorkTask();
        current.setId(2L);
        current.setOrderId(ORDER_ID);
        current.setStepNo(20);
        current.setStepName("老化测试");
        current.setStatus(WorkTaskStatus.PENDING.getCode());

        WorkTask prev = new WorkTask();
        prev.setId(1L);
        prev.setStepNo(10);
        prev.setStepName("外壳组装");
        prev.setStatus(WorkTaskStatus.COMPLETED.getCode());  // 已完成
        prev.setIsInspection(0);

        WorkTask started = new WorkTask();
        started.setId(2L);
        started.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());

        boolean[] startCalled = {false};
        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> startCalled[0] ? started : current),
                Stub.rule("selectPreviousStep", 2, inv -> prev),
                Stub.rule("startTask", 3, inv -> {
                    startCalled[0] = true;
                    return 1;
                }));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        var result = svc.startTask(2L, 4L);
        assertNotNull(result);
        assertEquals(1, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("首道工序无前道 → 直接允许开始")
    void testFirstStepAllowed() {
        WorkTask first = new WorkTask();
        first.setId(1L);
        first.setOrderId(ORDER_ID);
        first.setStepNo(10);
        first.setStatus(WorkTaskStatus.PENDING.getCode());

        WorkTask started = new WorkTask();
        started.setId(1L);
        started.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());

        boolean[] startCalled = {false};
        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> startCalled[0] ? started : first),
                Stub.rule("selectPreviousStep", 2, inv -> null),   // 无前道
                Stub.rule("startTask", 3, inv -> {
                    startCalled[0] = true;
                    return 1;
                }));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        var result = svc.startTask(1L, 4L);
        assertNotNull(result);
        assertEquals(WorkTaskStatus.IN_PROGRESS.getCode(), result.getStatus());
    }

    @Test
    @DisplayName("质检点工序处于 WAIT_INSPECTION 时，后道不能开始（质检门禁）")
    void testInspectionGate() {
        WorkTask current = new WorkTask();
        current.setId(3L);
        current.setOrderId(ORDER_ID);
        current.setStepNo(30);
        current.setStepName("包装入库");
        current.setStatus(WorkTaskStatus.PENDING.getCode());

        WorkTask prev = new WorkTask();
        prev.setId(2L);
        prev.setStepNo(20);
        prev.setStepName("老化测试");
        prev.setStatus(WorkTaskStatus.WAIT_INSPECTION.getCode());  // 待质检未放行
        prev.setIsInspection(1);

        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> current),
                Stub.rule("selectPreviousStep", 2, inv -> prev));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> svc.startTask(3L, 4L));
        assertEquals(ErrorCode.PROCESS_SEQUENCE_VIOLATION.getCode(), ex.getCode());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("前道为 SKIPPED 时，后道允许开始")
    void testPreviousSkippedAllowsNext() {
        WorkTask current = new WorkTask();
        current.setId(2L);
        current.setOrderId(ORDER_ID);
        current.setStepNo(20);
        current.setStatus(WorkTaskStatus.PENDING.getCode());

        WorkTask prev = new WorkTask();
        prev.setId(1L);
        prev.setStepNo(10);
        prev.setStatus(WorkTaskStatus.SKIPPED.getCode());
        prev.setIsInspection(0);

        WorkTask started = new WorkTask();
        started.setId(2L);
        started.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());

        boolean[] startCalled = {false};
        Recorder<WorkTaskMapper> mapper = Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", 1, inv -> startCalled[0] ? started : current),
                Stub.rule("selectPreviousStep", 2, inv -> prev),
                Stub.rule("startTask", 3, inv -> {
                    startCalled[0] = true;
                    return 1;
                }));
        WorkTaskService svc = new WorkTaskService(mapper.proxy(), FakeProductCatalog.empty());

        var result = svc.startTask(2L, 4L);
        assertEquals(WorkTaskStatus.IN_PROGRESS.getCode(), result.getStatus());
    }

    /* ==================== 报工单号生成 ==================== */

    @Test
    @DisplayName("报工单号应形如 RP+日期+序列，且两次生成不重复")
    void testNewReportNo() {
        WorkTaskService svc = new WorkTaskService(
                Recorder.of(WorkTaskMapper.class).proxy(), FakeProductCatalog.empty());
        String a = svc.newReportNo();
        String b = svc.newReportNo();
        assertNotNull(a);
        assertTrue(a.startsWith("RP"), "报工单号应以 RP 开头，实际: " + a);
        assertTrue(!a.equals(b), "两次生成的单号不应相同");
    }
}
