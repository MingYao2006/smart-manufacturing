package com.smart.production.service;

import com.smart.common.auth.RoleGuard;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.production.dto.ProductionDtos.DispatchDTO;
import com.smart.production.entity.WorkTask;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.statemachine.WorkTaskStatus;
import com.smart.production.support.FakeProductCatalog;
import com.smart.production.support.FakeUserRoles;
import com.smart.production.support.Stub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工序服务的角色与归属校验测试。
 *
 * <p>网关只能按"方法 + 路径"拦，看不出"这道工序派给谁了"，
 * 所以 <b>操作工只能动派给自己的工序</b> 这条口径必须在服务里读库后才能判，
 * 这里用 Mapper 桩把它钉住。断言里都会检查 Mapper 有没有被调用，
 * 确保越权请求是在改数据之前就被拒掉的。
 */
class WorkTaskServiceAccessTest {

    private static final Long TASK_ID = 1L;
    private static final Long ME = 11L;
    private static final Long OTHER = 12L;

    private Stub.Recorder<WorkTaskMapper> mapper;
    private WorkTaskService service;
    private WorkTask task;

    @BeforeEach
    void setUp() {
        task = new WorkTask();
        task.setId(TASK_ID);
        task.setOrderId(1L);
        task.setOrderNo("WO202610310010");
        task.setProductId(1L);
        task.setStepNo(10);
        task.setStepName("外壳组装");
        task.setWorkCenter("装配一车间");
        task.setPlanQuantity(new BigDecimal("100"));
        task.setReportedQuantity(BigDecimal.ZERO);
        task.setStatus(WorkTaskStatus.PENDING.getCode());
        task.setIsInspection(0);

        mapper = Stub.Recorder.of(WorkTaskMapper.class,
                Stub.rule("selectById", inv -> task),
                Stub.rule("dispatch", Stub.returnsInt(1)),
                Stub.rule("updateStatusCas", Stub.returnsInt(1)),
                Stub.rule("finishTask", Stub.returnsInt(1)),
                Stub.rule("startTask", Stub.returnsInt(1)),
                Stub.rule("selectPreviousStep", Stub.returns(null)),
                Stub.rule("countByOrderId", Stub.returnsInt(1)));
        service = new WorkTaskService(mapper.proxy(), FakeProductCatalog.healthy(), FakeUserRoles.defaults());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void loginAs(String role, Long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", role);
        request.addHeader("X-User-Id", String.valueOf(userId));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private DispatchDTO dispatchTo(Long operatorId) {
        DispatchDTO dto = new DispatchDTO();
        dto.setOperatorId(operatorId);
        dto.setOperatorName("王工");
        dto.setWorkCenter("装配一车间");
        return dto;
    }

    @Test
    @DisplayName("派工：计划员放行，其他角色在写库前就被拒")
    void dispatchIsPlannerOnly() {
        loginAs(RoleGuard.PLANNER, 2L);
        assertEquals(TASK_ID, service.dispatch(TASK_ID, dispatchTo(ME)).getId());
        assertEquals(1, mapper.callCount("dispatch"));

        loginAs(RoleGuard.WAREHOUSE, 3L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.dispatch(TASK_ID, dispatchTo(ME)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("仓库管理员"), e.getMessage());
        // 仍然只有计划员那一次落库
        assertEquals(1, mapper.callCount("dispatch"));
    }

    @Test
    @DisplayName("派工：普通工序只能派给车间操作工，派给质检员/计划员一律拒绝")
    void dispatchToNonOperatorIsRejected() {
        loginAs(RoleGuard.PLANNER, 2L);

        // 目标只有质检员 → 普通工序不允许
        service = new WorkTaskService(mapper.proxy(), FakeProductCatalog.healthy(),
                FakeUserRoles.defaults().with(OTHER, RoleGuard.INSPECTOR));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.dispatch(TASK_ID, dispatchTo(OTHER)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("车间操作工"), e.getMessage());
        assertEquals(0, mapper.callCount("dispatch"));

        // 计划员自派：即便该账号同时兼有操作工角色，也不允许把工序派给自己
        service = new WorkTaskService(mapper.proxy(), FakeProductCatalog.healthy(),
                FakeUserRoles.defaults().with(2L, RoleGuard.PLANNER, RoleGuard.OPERATOR));
        BusinessException self = assertThrows(BusinessException.class,
                () -> service.dispatch(TASK_ID, dispatchTo(2L)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), self.getCode());
        assertTrue(self.getMessage().contains("计划员"), self.getMessage());
        assertEquals(0, mapper.callCount("dispatch"));
    }

    @Test
    @DisplayName("派工：质检点工序只能派给质检员，派给操作工被拒")
    void inspectionTaskDispatchRequiresInspector() {
        task.setIsInspection(1);
        loginAs(RoleGuard.PLANNER, 2L);
        service = new WorkTaskService(mapper.proxy(), FakeProductCatalog.healthy(),
                FakeUserRoles.defaults().with(ME, RoleGuard.OPERATOR)
                        .with(OTHER, RoleGuard.INSPECTOR));

        // 派给操作工 → 拒绝（质检点必须由质检员做）
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.dispatch(TASK_ID, dispatchTo(ME)));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("质检员"), e.getMessage());
        assertEquals(0, mapper.callCount("dispatch"));

        // 派给质检员 → 放行并落库
        assertEquals(TASK_ID, service.dispatch(TASK_ID, dispatchTo(OTHER)).getId());
        assertEquals(1, mapper.callCount("dispatch"));
    }

    @Test
    @DisplayName("派工：管理员可兜底接收任意工序的派工")
    void adminCanReceiveAnyDispatch() {
        loginAs(RoleGuard.PLANNER, 2L);
        service = new WorkTaskService(mapper.proxy(), FakeProductCatalog.healthy(),
                FakeUserRoles.defaults().with(OTHER, RoleGuard.ADMIN));

        assertEquals(TASK_ID, service.dispatch(TASK_ID, dispatchTo(OTHER)).getId());
        assertEquals(1, mapper.callCount("dispatch"));
    }

    @Test
    @DisplayName("开工：操作工只能动派给本人的工序")
    void operatorCanOnlyStartOwnTask() {
        task.setOperatorId(ME);
        task.setStatus(WorkTaskStatus.COMPLETED.getCode());
        loginAs(RoleGuard.OPERATOR, ME);

        // 已完成 → 卡在状态机而不是权限，说明角色与归属两道校验都过了
        BusinessException state = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), state.getCode());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("开工：别人的工序 → 40300，不碰数据库")
    void operatorCannotStartOthersTask() {
        task.setOperatorId(OTHER);
        loginAs(RoleGuard.OPERATOR, ME);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("只能操作派给本人的工序"), e.getMessage());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("开工：未派工的工序不能被操作工自己抢")
    void unassignedTaskIsNotClaimable() {
        task.setOperatorId(null);
        loginAs(RoleGuard.OPERATOR, ME);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("暂停/恢复同样受执行者归属约束")
    void pauseChecksOwnershipToo() {
        task.setOperatorId(OTHER);
        task.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        loginAs(RoleGuard.OPERATOR, ME);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.pauseTask(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
    }

    @Test
    @DisplayName("质检员可以开工/完成派给自己的质检点工序（如老化测试）")
    void inspectorCanExecuteOwnInspectionTask() {
        task.setOperatorId(ME);
        task.setIsInspection(1);
        loginAs(RoleGuard.INSPECTOR, ME);

        // 走到 CAS 说明角色 + 归属两道校验都过了（PENDING → IN_PROGRESS）
        service.startTask(TASK_ID, ME);
        assertEquals(1, mapper.callCount("startTask"));

        // 进行中的质检点工序由质检员自己完成 → 进入待质检（WAIT_INSPECTION）
        task.setStatus(WorkTaskStatus.IN_PROGRESS.getCode());
        service.completeTask(TASK_ID, ME);
        assertEquals(1, mapper.callCount("finishTask"));
    }

    @Test
    @DisplayName("质检员不能执行派给别人的工序")
    void inspectorCannotExecuteOthersTask() {
        task.setOperatorId(OTHER);
        task.setIsInspection(1);
        loginAs(RoleGuard.INSPECTOR, ME);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("只能操作派给本人的工序"), e.getMessage());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("质检放行：操作工不能自己判合格，只有质检员可以")
    void inspectionGateIsInspectorOnly() {
        task.setStatus(WorkTaskStatus.WAIT_INSPECTION.getCode());
        task.setOperatorId(ME);
        loginAs(RoleGuard.OPERATOR, ME);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.passInspection(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertTrue(e.getMessage().contains("质检放行"), e.getMessage());
        assertEquals(0, mapper.callCount("finishTask"));

        loginAs(RoleGuard.INSPECTOR, 5L);
        // 质检员放行能走到 CAS，说明门禁按角色放开（返回的是放行后的视图）
        service.passInspection(TASK_ID, 5L);
        assertEquals(1, mapper.callCount("finishTask"));
    }

    @Test
    @DisplayName("计划员不能替操作工开工/完工（工序执行只归操作工）")
    void plannerCannotExecuteTasks() {
        loginAs(RoleGuard.PLANNER, 2L);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.FORBIDDEN.getCode(), e.getCode());
        assertEquals(0, mapper.callCount("startTask"));
    }

    @Test
    @DisplayName("无登录身份（MQ 消费者/定时任务）时不按角色拒，业务流照常")
    void noIdentitySkipsRoleCheck() {
        RequestContextHolder.resetRequestAttributes();
        task.setStatus(WorkTaskStatus.COMPLETED.getCode());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.startTask(TASK_ID, ME));
        assertEquals(ErrorCode.STATE_TRANSITION_DENIED.getCode(), e.getCode());
    }
}
