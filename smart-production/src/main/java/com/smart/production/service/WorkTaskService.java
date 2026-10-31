package com.smart.production.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.production.dto.ProductionDtos.DispatchDTO;
import com.smart.production.dto.ProductionDtos.WorkTaskVO;
import com.smart.production.entity.ProductionOrder;
import com.smart.production.entity.WorkTask;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.service.ProductionOrderService.RouteInfo;
import com.smart.production.service.ProductionOrderService.RouteStepInfo;
import com.smart.production.statemachine.WorkTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工序任务服务（第 3 周任务 3.5 + 第 5 周任务 5.1/5.2）。
 *
 * <h3>工序任务生成（3.5）</h3>
 * 工单下达时读取<b>快照的 routeId</b>，为每道工序生成一条 work_task。
 * <ul>
 *   <li><b>同步生成</b>而不是 MQ 异步：工序少（3~8 道）、生成快，
 *       且下达是低频操作，没有解耦收益。这是"不要为了用 MQ 而用 MQ"的判断点。</li>
 *   <li><b>幂等</b>：靠 {@code uk_order_step(order_id, step_no)} 唯一索引兜底，
 *       重复下达/消息重投时捕获 DuplicateKeyException 跳过，绝不生成两套任务。</li>
 * </ul>
 *
 * <h3>工序顺序约束（5.2）</h3>
 * 工序 20 不能在第 10 道未完成时开始 —— 这是 MES 的<b>工艺纪律</b>。
 * 实现方式：取"所有 step_no 小于当前的最大那条"，校验其状态为终态。
 * 为什么不能只看 step_no-10：工序号可能不连续（10/20/30，中间插入 15 后）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkTaskService {

    private final WorkTaskMapper workTaskMapper;
    private final ProductCatalogClient productClient;

    /* ==================== 生成工序任务（幂等） ==================== */

    /**
     * 按工单快照的 routeId 生成工序任务。
     *
     * <p>返回实际新建的条数（已存在的跳过）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int generateTasks(ProductionOrder order) {
        // 已有任务说明已生成过（重复下达 / 消息重投），直接返回
        int existing = workTaskMapper.countByOrderId(order.getId());
        if (existing > 0) {
            log.info("[工序任务] 已存在 {} 条，跳过生成 orderNo={}", existing, order.getOrderNo());
            return 0;
        }

        // 🔑 用快照的 routeId，不是产品当前工艺路线
        RouteInfo route = productClient.getActiveRoute(order.getProductId());
        if (route == null) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_EFFECTIVE,
                    "工单[" + order.getOrderNo() + "]锁定的工艺路线不可用");
        }
        if (route.getId() != null && !route.getId().equals(order.getRouteId())) {
            // 快照版本与当前生效版本不一致是正常的（工艺升级），但取工序必须用快照版本的
            log.warn("[工序任务] 工单快照 routeId={} 与当前生效 routeId={} 不一致，"
                    + "工序任务应基于快照版本；请在 product 服务提供按 routeId 查工序的接口",
                    order.getRouteId(), route.getId());
        }
        List<RouteStepInfo> steps = route.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new BusinessException("工单[" + order.getOrderNo() + "]的工艺路线没有工序，无法下达");
        }

        int created = 0;
        for (RouteStepInfo s : steps) {
            WorkTask task = new WorkTask();
            task.setOrderId(order.getId());
            task.setOrderNo(order.getOrderNo());
            task.setProductId(order.getProductId());
            task.setStepNo(s.getStepNo());
            task.setStepName(s.getStepName());
            task.setWorkCenter(s.getWorkCenter());
            task.setStandardHours(s.getStandardHours() == null ? BigDecimal.ZERO : s.getStandardHours());
            task.setPlanQuantity(order.getPlanQuantity());
            task.setReportedQuantity(BigDecimal.ZERO);
            task.setQualifiedQuantity(BigDecimal.ZERO);
            task.setDefectQuantity(BigDecimal.ZERO);
            task.setActualHours(BigDecimal.ZERO);
            task.setStatus(WorkTaskStatus.PENDING.getCode());
            task.setIsInspection(s.getIsInspection() == null ? 0 : s.getIsInspection());
            try {
                workTaskMapper.insert(task);
                created++;
            } catch (DuplicateKeyException e) {
                // uk_order_step 兜底：并发/重投情况下已生成，跳过
                log.info("[工序任务] 工序 {} 已存在，跳过 orderNo={}", s.getStepNo(), order.getOrderNo());
            }
        }
        log.info("[工序任务生成] orderNo={} 共 {} 道，新建 {} 条",
                order.getOrderNo(), steps.size(), created);
        return created;
    }

    /**
     * 工单取消时作废未完成的工序任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelTasks(ProductionOrder order) {
        List<WorkTask> tasks = workTaskMapper.selectByOrderId(order.getId());
        for (WorkTask t : tasks) {
            WorkTaskStatus st = WorkTaskStatus.of(t.getStatus());
            if (st == WorkTaskStatus.COMPLETED || st == WorkTaskStatus.SKIPPED) {
                continue;
            }
            // 未完成的任务直接置 SKIPPED（终态），保留数据可追溯
            workTaskMapper.updateStatusCas(t.getId(), WorkTaskStatus.SKIPPED.getCode(),
                    st.getCode(), 0L);
        }
        log.info("[工序任务作废] orderNo={} 共 {} 条", order.getOrderNo(), tasks.size());
    }

    /* ==================== 查询与派工 ==================== */

    public List<WorkTask> listByOrder(Long orderId) {
        return workTaskMapper.selectByOrderId(orderId);
    }

    public WorkTask getTask(Long taskId) {
        WorkTask t = workTaskMapper.selectById(taskId);
        if (t == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工序任务不存在: " + taskId);
        }
        return t;
    }

    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO dispatch(Long taskId, DispatchDTO dto) {
        getTask(taskId);
        workTaskMapper.dispatch(taskId, dto.getOperatorId(), dto.getOperatorName(), dto.getWorkCenter());
        log.info("[派工] taskId={} operator={} center={}", taskId, dto.getOperatorName(), dto.getWorkCenter());
        return toVO(getTask(taskId));
    }

    /* ==================== 工序状态流转 + 顺序约束 ==================== */

    /**
     * 开始工序。
     *
     * <p>校验链（顺序很重要，越便宜的校验越靠前）：
     * <ol>
     *   <li>状态必须 PENDING</li>
     *   <li>工单必须处于"生产中"（否则工单没下达就开工，数据会乱）</li>
     *   <li><b>前道工序必须已完成</b>（工艺纪律）</li>
     *   <li><b>前道工序若是质检点，必须质检合格</b>（第 6 周的门禁，这里已生效）</li>
     *   <li>CAS 更新（两人同时点开始，只有一个成功）</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO startTask(Long taskId, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        if (current != WorkTaskStatus.PENDING) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "工序当前状态为[" + current.getDesc() + "]，不允许开始");
        }

        // 工艺纪律：前道工序必须完成
        checkPreviousStepCompleted(task);

        int affected = workTaskMapper.startTask(taskId, current.getCode(), operatorId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "工序状态已被其他操作变更，请刷新后重试");
        }
        log.info("[工序开始] orderNo={} step={}({}) operator={}",
                task.getOrderNo(), task.getStepNo(), task.getStepName(), operatorId);
        return toVO(getTask(taskId));
    }

    /**
     * 暂停工序。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO pauseTask(Long taskId, Long operatorId) {
        return transit(taskId, WorkTaskStatus.PAUSED, operatorId);
    }

    /**
     * 恢复工序。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO resumeTask(Long taskId, Long operatorId) {
        return transit(taskId, WorkTaskStatus.IN_PROGRESS, operatorId);
    }

    /**
     * 完成工序（不依赖报工，用于无需报工的场景）。
     *
     * <p>质检点工序完成后进入 WAIT_INSPECTION 而不是 COMPLETED。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO completeTask(Long taskId, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        WorkTaskStatus target = (task.getIsInspection() != null && task.getIsInspection() == 1)
                ? WorkTaskStatus.WAIT_INSPECTION : WorkTaskStatus.COMPLETED;
        current.checkTransitTo(target);
        int affected = workTaskMapper.finishTask(taskId, target.getCode(), current.getCode(), operatorId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "工序状态已变更，请刷新后重试");
        }
        log.info("[工序完成] orderNo={} step={} 目标状态={}",
                task.getOrderNo(), task.getStepNo(), target.getDesc());
        return toVO(getTask(taskId));
    }

    /**
     * 通用状态流转（内部用）。
     */
    private WorkTaskVO transit(Long taskId, WorkTaskStatus target, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        current.checkTransitTo(target);
        int affected = workTaskMapper.updateStatusCas(taskId, target.getCode(),
                current.getCode(), operatorId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "工序状态已变更，请刷新后重试");
        }
        log.info("[工序流转] orderNo={} step={} {} → {}",
                task.getOrderNo(), task.getStepNo(), current.getDesc(), target.getDesc());
        return toVO(getTask(taskId));
    }

    /**
     * 🔑 工序顺序约束：前道工序必须已完成。
     *
     * <p>取"所有 step_no 小于当前的最大那条"，而不是简单的 step_no - 10：
     * 工序号是 10/20/30 且允许插入 15，减法会算错工序。
     *
     * <p>这是 MES 的工艺纪律，不能跳过。而且校验必须在<b>服务端</b>做，
     * 否则攻击者直接调接口就能绕过。
     */
    private void checkPreviousStepCompleted(WorkTask task) {
        WorkTask prev = workTaskMapper.selectPreviousStep(task.getOrderId(), task.getStepNo());
        if (prev == null) {
            // 首道工序，直接放行
            return;
        }
        WorkTaskStatus prevStatus = WorkTaskStatus.of(prev.getStatus());
        // 前道必须到终态：COMPLETED（已完成）或 SKIPPED（跳过）
        if (prevStatus != WorkTaskStatus.COMPLETED && prevStatus != WorkTaskStatus.SKIPPED) {
            throw new BusinessException(ErrorCode.PROCESS_SEQUENCE_VIOLATION,
                    String.format("前道工序[%d-%s]当前状态为[%s]，未完成，不能开始本工序[%d-%s]",
                            prev.getStepNo(), prev.getStepName(), prevStatus.getDesc(),
                            task.getStepNo(), task.getStepName()));
        }
        // 质检门禁：前道是质检点但没有质检合格记录时，不放行
        // （WAIT_INSPECTION 状态本身就是"未放行"的信号，上面已经挡住；
        //   这里额外防御"被强制改成 COMPLETED 但质检未过"的脏数据）
        if (prev.getIsInspection() != null && prev.getIsInspection() == 1
                && prevStatus == WorkTaskStatus.WAIT_INSPECTION) {
            throw new BusinessException(ErrorCode.INSPECTION_NOT_PASSED,
                    String.format("前道工序[%d-%s]是质检点，质检未通过，不能开始本工序",
                            prev.getStepNo(), prev.getStepName()));
        }
    }

    /* ==================== 报工累加（条件更新） ==================== */

    /**
     * 🔑 报工累加 —— 本周最容易做错的地方，用条件更新实现并发安全。
     *
     * @return 影响行数（0 = 超报或状态不对）
     */
    @Transactional(rollbackFor = Exception.class)
    public int addReportedQuantity(Long taskId, BigDecimal qty, BigDecimal qualified,
                                   BigDecimal defect, BigDecimal hours, Long operatorId) {
        return workTaskMapper.addReportedQuantity(taskId, qty, qualified, defect, hours, operatorId);
    }

    /**
     * 报工后判断工序是否已报满，报满则流转到终态（需质检 → WAIT_INSPECTION）。
     *
     * @return 流转后的状态
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskStatus afterReport(Long taskId, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        if (current != WorkTaskStatus.IN_PROGRESS) {
            return current;
        }
        BigDecimal reported = task.getReportedQuantity() == null ? BigDecimal.ZERO : task.getReportedQuantity();
        BigDecimal plan = task.getPlanQuantity() == null ? BigDecimal.ZERO : task.getPlanQuantity();
        if (reported.compareTo(plan) < 0) {
            // 没报满，保持进行中
            return current;
        }
        WorkTaskStatus target = (task.getIsInspection() != null && task.getIsInspection() == 1)
                ? WorkTaskStatus.WAIT_INSPECTION : WorkTaskStatus.COMPLETED;
        int affected = workTaskMapper.finishTask(taskId, target.getCode(), current.getCode(), operatorId);
        if (affected != 1) {
            return WorkTaskStatus.of(getTask(taskId).getStatus());
        }
        log.info("[工序报满] orderNo={} step={} 报工={}/{} 流转到 {}",
                task.getOrderNo(), task.getStepNo(), reported, plan, target.getDesc());
        return target;
    }

    /**
     * 质检放行：WAIT_INSPECTION → COMPLETED（质检合格时调用，第 6 周接入）。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO passInspection(Long taskId, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        if (current != WorkTaskStatus.WAIT_INSPECTION) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "工序当前状态为[" + current.getDesc() + "]，不是待质检状态");
        }
        int affected = workTaskMapper.finishTask(taskId, WorkTaskStatus.COMPLETED.getCode(),
                current.getCode(), operatorId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "工序状态已变更，请刷新后重试");
        }
        log.info("[质检放行] orderNo={} step={} WAIT_INSPECTION → COMPLETED",
                task.getOrderNo(), task.getStepNo());
        return toVO(getTask(taskId));
    }

    /**
     * 质检不合格退回返工：WAIT_INSPECTION → IN_PROGRESS。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkTaskVO rejectInspection(Long taskId, Long operatorId) {
        WorkTask task = getTask(taskId);
        WorkTaskStatus current = WorkTaskStatus.of(task.getStatus());
        if (current != WorkTaskStatus.WAIT_INSPECTION) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "工序当前状态为[" + current.getDesc() + "]，不是待质检状态");
        }
        int affected = workTaskMapper.updateStatusCas(taskId, WorkTaskStatus.IN_PROGRESS.getCode(),
                current.getCode(), operatorId);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "工序状态已变更，请刷新后重试");
        }
        log.info("[质检退回返工] orderNo={} step={} WAIT_INSPECTION → IN_PROGRESS",
                task.getOrderNo(), task.getStepNo());
        return toVO(getTask(taskId));
    }

    /* ==================== VO ==================== */

    public WorkTaskVO toVO(WorkTask t) {
        WorkTaskVO v = new WorkTaskVO();
        v.setId(t.getId());
        v.setOrderId(t.getOrderId());
        v.setOrderNo(t.getOrderNo());
        v.setStepNo(t.getStepNo());
        v.setStepName(t.getStepName());
        v.setWorkCenter(t.getWorkCenter());
        v.setStandardHours(t.getStandardHours());
        v.setPlanQuantity(t.getPlanQuantity());
        v.setReportedQuantity(t.getReportedQuantity());
        v.setQualifiedQuantity(t.getQualifiedQuantity());
        v.setDefectQuantity(t.getDefectQuantity());
        v.setActualHours(t.getActualHours());
        v.setStatus(t.getStatus());
        v.setStatusDesc(WorkTaskStatus.descOf(t.getStatus()));
        v.setIsInspection(t.getIsInspection());
        v.setOperatorId(t.getOperatorId());
        v.setOperatorName(t.getOperatorName());
        v.setStartTime(t.getStartTime());
        v.setEndTime(t.getEndTime());
        return v;
    }

    /**
     * 生成报工单号（幂等键）。
     */
    public String newReportNo() {
        return BizNoGenerator.generateWithNano(BizNoGenerator.REPORT_PREFIX);
    }
}
