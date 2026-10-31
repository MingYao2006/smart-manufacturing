package com.smart.production.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.production.dto.ProductionDtos.ReportCreateDTO;
import com.smart.production.dto.ProductionDtos.ReportResultVO;
import com.smart.production.entity.ProductionReport;
import com.smart.production.entity.WorkTask;
import com.smart.production.mapper.ProductionReportMapper;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.mq.MqConstants;
import com.smart.production.statemachine.WorkTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 生产报工服务（第 5 周任务 5.3 / 5.4 / 5.5 / 5.6）。
 *
 * <h3>报工流程（严格按需求文档 4.6）</h3>
 * <pre>
 *  ① 校验报工单号幂等（已存在 → 直接返回，不重复处理）
 *  ② 校验 合格数 + 不良数 = 报工数（服务端校验，前端不可信）
 *  ③ 查工序任务，状态必须 IN_PROGRESS
 *  ④ 条件更新累加 reported_quantity（防超报，并发安全）
 *  ⑤ 插入 production_report（明细账）
 *  ⑥ 判断是否报满 → 流转终态（需质检 → WAIT_INSPECTION）
 *  ⑦ 事务内写 mq_outbox（报工完成事件）
 *  ⑧ 事务提交后由 OutboxSenderService 异步投递 → 消费者重算工单进度
 * </pre>
 *
 * <h3>为什么第 ⑦ 步用 outbox 而不是直接发 MQ</h3>
 * 直接发的问题："事务还没提交就发了消息" → 消费者可能读到旧数据（甚至看到不存在的记录）。
 * 更糟的是"事务回滚了但消息已发"→ 消费者处理一个不存在的报工。
 * outbox 保证"消息只在事务提交后才可能被投递"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionReportService {

    private final WorkTaskMapper workTaskMapper;
    private final ProductionReportMapper reportMapper;
    private final WorkTaskService workTaskService;
    private final MessagePublisher outboxSender;
    private final ProductCatalogClient productClient;

    /**
     * 提交报工。
     *
     * <p>整个方法在<b>一个本地事务</b>内：报工累加、明细插入、outbox 写入要么全成功要么全失败。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReportResultVO report(ReportCreateDTO dto) {
        // ① 幂等：报工单号已存在则直接返回，不重复处理
        String reportNo = dto.getReportNo();
        if (reportNo != null && !reportNo.isBlank()) {
            if (reportMapper.countByReportNo(reportNo) > 0) {
                log.info("[报工幂等] 单号已存在，直接返回 reportNo={}", reportNo);
                ProductionReport exist = reportMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProductionReport>()
                                .eq(ProductionReport::getReportNo, reportNo));
                return buildIdempotentResult(exist);
            }
        } else {
            reportNo = BizNoGenerator.generateWithNano(BizNoGenerator.REPORT_PREFIX);
        }

        // ② 数量基础校验（合格 + 不良 = 报工）
        validateQuantity(dto);

        // ③ 工序任务必须处于进行中
        WorkTask task = workTaskService.getTask(dto.getTaskId());
        WorkTaskStatus taskStatus = WorkTaskStatus.of(task.getStatus());
        if (taskStatus != WorkTaskStatus.IN_PROGRESS) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "工序[" + task.getStepNo() + "-" + task.getStepName() + "]当前状态为["
                            + taskStatus.getDesc() + "]，只有[进行中]的工序可以报工");
        }

        BigDecimal hours = dto.getActualHours() == null ? BigDecimal.ZERO : dto.getActualHours();
        Long operatorId = dto.getOperatorId();

        // ④ 条件更新累加（防超报，并发安全）
        int affected = workTaskMapper.addReportedQuantity(
                dto.getTaskId(), dto.getReportQuantity(), dto.getQualifiedQuantity(),
                dto.getDefectQuantity(), hours, operatorId);
        if (affected != 1) {
            // 影响行数 0 → 超报，或状态被并发改变
            WorkTask latest = workTaskMapper.selectById(dto.getTaskId());
            BigDecimal reported = latest.getReportedQuantity() == null
                    ? BigDecimal.ZERO : latest.getReportedQuantity();
            throw new BusinessException(ErrorCode.QUANTITY_EXCEED_PLAN,
                    String.format("报工数量超出计划数量：计划 %s，已报 %s，本次 %s",
                            latest.getPlanQuantity(), reported, dto.getReportQuantity()));
        }

        // ⑤ 插入报工明细（明细账）
        ProductionReport report = new ProductionReport();
        report.setReportNo(reportNo);
        report.setOrderId(task.getOrderId());
        report.setOrderNo(task.getOrderNo());
        report.setTaskId(task.getId());
        report.setStepNo(task.getStepNo());
        report.setStepName(task.getStepName());
        report.setReportQuantity(dto.getReportQuantity());
        report.setQualifiedQuantity(dto.getQualifiedQuantity());
        report.setDefectQuantity(dto.getDefectQuantity());
        report.setActualHours(hours);
        report.setOperatorId(operatorId);
        report.setOperatorName(dto.getOperatorName());
        report.setReportTime(LocalDateTime.now());
        report.setRemark(dto.getRemark());
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引兜底。整个事务回滚，累加也不会生效
            throw new BusinessException(ErrorCode.DUPLICATE_KEY, "报工单号重复提交: " + reportNo);
        }

        // ⑥ 判断是否报满 → 流转终态
        WorkTaskStatus afterStatus = workTaskService.afterReport(task.getId(), operatorId);

        // ⑦ 事务内写 outbox（报工完成事件）
        MqConstants.ReportCompletedEvent event = new MqConstants.ReportCompletedEvent(
                report.getId(), reportNo, task.getOrderId(), task.getOrderNo(),
                task.getId(), task.getStepNo(),
                dto.getReportQuantity(), dto.getQualifiedQuantity(), dto.getDefectQuantity(),
                report.getReportTime(), UUID.randomUUID().toString());
        outboxSender.enqueue("report:" + report.getId() + ":order-progress",
                MqConstants.EXCHANGE, MqConstants.RK_REPORT_COMPLETED, event);

        WorkTask updated = workTaskMapper.selectById(task.getId());
        log.info("[报工成功] orderNo={} step={} 本次={} 累计={}/{} 状态={} reportNo={}",
                task.getOrderNo(), task.getStepNo(), dto.getReportQuantity(),
                updated.getReportedQuantity(), updated.getPlanQuantity(),
                WorkTaskStatus.descOf(afterStatus.getCode()), reportNo);

        ReportResultVO vo = new ReportResultVO();
        vo.setReportId(report.getId());
        vo.setReportNo(reportNo);
        vo.setTaskId(task.getId());
        vo.setReportedQuantity(updated.getReportedQuantity());
        vo.setPlanQuantity(updated.getPlanQuantity());
        vo.setTaskStatus(updated.getStatus());
        vo.setTaskStatusDesc(WorkTaskStatus.descOf(updated.getStatus()));
        vo.setTaskCompleted(afterStatus == WorkTaskStatus.COMPLETED
                || afterStatus == WorkTaskStatus.WAIT_INSPECTION);
        vo.setMessage(afterStatus == WorkTaskStatus.WAIT_INSPECTION
                ? "报工成功，工序已报满，等待质检放行"
                : (afterStatus == WorkTaskStatus.COMPLETED ? "报工成功，工序已完成" : "报工成功"));
        return vo;
    }

    /**
     * 数量校验：服务端必须校验 合格数 + 不良数 = 报工数。
     *
     * <p>为什么不能只靠前端：前端校验可以被绕过（直接调接口），
     * 而这三者不一致会让合格率、良品统计全部失真。
     */
    private void validateQuantity(ReportCreateDTO dto) {
        if (!BizNoGenerator.gtZero(dto.getReportQuantity())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "报工数量必须大于 0");
        }
        if (!BizNoGenerator.notNegative(dto.getQualifiedQuantity())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "合格数不能为负数");
        }
        if (!BizNoGenerator.notNegative(dto.getDefectQuantity())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "不良数不能为负数");
        }
        BigDecimal sum = dto.getQualifiedQuantity().add(dto.getDefectQuantity());
        if (sum.compareTo(dto.getReportQuantity()) != 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    String.format("合格数(%s) + 不良数(%s) 必须等于报工数(%s)，当前合计 %s",
                            dto.getQualifiedQuantity(), dto.getDefectQuantity(),
                            dto.getReportQuantity(), sum));
        }
    }

    private ReportResultVO buildIdempotentResult(ProductionReport exist) {
        ReportResultVO vo = new ReportResultVO();
        if (exist == null) {
            vo.setMessage("报工单号已处理过");
            return vo;
        }
        WorkTask task = workTaskMapper.selectById(exist.getTaskId());
        vo.setReportId(exist.getId());
        vo.setReportNo(exist.getReportNo());
        vo.setTaskId(exist.getTaskId());
        vo.setReportedQuantity(task == null ? null : task.getReportedQuantity());
        vo.setPlanQuantity(task == null ? null : task.getPlanQuantity());
        vo.setTaskStatus(task == null ? null : task.getStatus());
        vo.setTaskStatusDesc(task == null ? null : WorkTaskStatus.descOf(task.getStatus()));
        vo.setTaskCompleted(task != null && WorkTaskStatus.of(task.getStatus()).isTerminal());
        vo.setMessage("该报工单号已处理过（幂等返回，未重复累加）");
        return vo;
    }

    /**
     * 查询某工序的报工明细。
     */
    public java.util.List<ProductionReport> listByTask(Long taskId) {
        return reportMapper.selectByTaskId(taskId);
    }

    public java.util.List<ProductionReport> listByOrder(Long orderId) {
        return reportMapper.selectByOrderId(orderId);
    }
}
