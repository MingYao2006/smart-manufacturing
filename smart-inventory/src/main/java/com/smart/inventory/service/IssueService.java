package com.smart.inventory.service;

import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.inventory.dto.InventoryDtos.IssueRequest;
import com.smart.inventory.dto.InventoryDtos.IssueResultVO;
import com.smart.inventory.dto.InventoryDtos.ReturnRequest;
import com.smart.inventory.entity.InventoryTransaction;
import com.smart.inventory.entity.MaterialIssue;
import com.smart.inventory.entity.MaterialIssueItem;
import com.smart.inventory.mapper.InventoryTransactionMapper;
import com.smart.inventory.mapper.MaterialIssueItemMapper;
import com.smart.inventory.mapper.MaterialIssueMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 领料 / 退料服务（第 4 周任务 4.4）。
 *
 * <h3>领料单的角色</h3>
 * 领料是"仓库 → 车间"的物料转移，必须有单据凭证：
 * <ul>
 *   <li>{@code issueNo} 唯一索引作为<b>幂等键</b>，防用户双击/网络重试导致重复领料</li>
 *   <li>明细表记录<b>批次号</b>，这是追溯链路的起点</li>
 * </ul>
 *
 * <p>幂等的实现方式：先查 {@code issueNo} 是否存在，存在则直接返回成功（不重复处理）；
 * 并发下靠唯一索引兜底（捕获 DuplicateKeyException 后返回幂等成功）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IssueService {

    private static final int ISSUE_TYPE_ISSUE = 1;
    private static final int ISSUE_TYPE_RETURN = 2;
    private static final int STATUS_CONFIRMED = 2;

    private final MaterialIssueMapper issueMapper;
    private final MaterialIssueItemMapper issueItemMapper;
    private final InventoryTransactionMapper transactionMapper;
    private final InventoryService inventoryService;

    /**
     * 领料。
     */
    @Transactional(rollbackFor = Exception.class)
    public IssueResultVO issue(IssueRequest req) {
        String issueNo = req.getIssueNo();
        if (issueNo == null || issueNo.isBlank()) {
            issueNo = BizNoGenerator.generateWithNano(BizNoGenerator.ISSUE_PREFIX);
        } else {
            // 幂等：单号已存在直接返回成功，不重复扣库存
            if (issueMapper.countByIssueNo(issueNo) > 0) {
                log.info("[领料幂等] 单号已存在，直接返回 issueNo={}", issueNo);
                return idempotentResult(issueNo);
            }
        }

        Long operatorId = req.getOperatorId() != null ? req.getOperatorId() : UserContextHolder.currentUserId();
        String operatorName = req.getOperatorName() != null
                ? req.getOperatorName() : UserContextHolder.currentUserName();

        // 先做库存/预留校验与扣减（内部是条件更新 + 流水）
        inventoryService.issueItems(req.getOrderId(), req.getOrderNo(), req.getWarehouseId(),
                req.getItems(), issueNo, operatorId, operatorName);

        // 写领料单主表
        MaterialIssue issue = new MaterialIssue();
        issue.setIssueNo(issueNo);
        issue.setOrderId(req.getOrderId());
        issue.setOrderNo(req.getOrderNo());
        issue.setWarehouseId(req.getWarehouseId() == null ? 1L : req.getWarehouseId());
        issue.setIssueType(ISSUE_TYPE_ISSUE);
        issue.setStatus(STATUS_CONFIRMED);
        issue.setTotalQuantity(req.getItems().stream()
                .map(i -> i.getQuantity() == null ? BigDecimal.ZERO : i.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        issue.setIssueTime(LocalDateTime.now());
        issue.setOperatorId(operatorId);
        issue.setOperatorName(operatorName);
        issue.setRemark(req.getRemark());
        try {
            issueMapper.insert(issue);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：唯一索引兜底，整个事务回滚（库存不会被扣两次）
            throw new BusinessException(ErrorCode.DUPLICATE_KEY, "领料单号重复提交: " + issueNo);
        }

        // 写领料明细（含批次号 —— 追溯关键）
        for (var item : req.getItems()) {
            MaterialIssueItem mii = new MaterialIssueItem();
            mii.setIssueId(issue.getId());
            mii.setMaterialId(item.getMaterialId());
            mii.setBatchNo(item.getBatchNo());
            mii.setQuantity(item.getQuantity());
            issueItemMapper.insert(mii);
        }

        log.info("[领料成功] issueNo={} orderId={} 明细 {} 项 合计 {}",
                issueNo, req.getOrderId(), req.getItems().size(), issue.getTotalQuantity());

        IssueResultVO vo = new IssueResultVO();
        vo.setIssueId(issue.getId());
        vo.setIssueNo(issueNo);
        vo.setOrderId(req.getOrderId());
        vo.setItemCount(req.getItems().size());
        vo.setTotalQuantity(issue.getTotalQuantity());
        vo.setIdempotent(false);
        vo.setMessage("领料成功");
        return vo;
    }

    /**
     * 退料。
     *
     * <p>退料不动预留（预留已在领料时消耗），只把物料加回仓库并记反向流水。
     */
    @Transactional(rollbackFor = Exception.class)
    public IssueResultVO returnMaterial(ReturnRequest req) {
        String returnNo = req.getReturnNo();
        if (returnNo == null || returnNo.isBlank()) {
            returnNo = BizNoGenerator.generateWithNano("RT");
        } else if (issueMapper.countByIssueNo(returnNo) > 0) {
            log.info("[退料幂等] 单号已存在 returnNo={}", returnNo);
            IssueResultVO vo = idempotentResult(returnNo);
            vo.setMessage("该退料单号已处理过（幂等返回）");
            return vo;
        }

        Long operatorId = req.getOperatorId() != null ? req.getOperatorId() : UserContextHolder.currentUserId();
        String operatorName = req.getOperatorName() != null
                ? req.getOperatorName() : UserContextHolder.currentUserName();

        inventoryService.returnItems(req.getOrderId(), req.getOrderNo(), req.getWarehouseId(),
                req.getItems(), returnNo, operatorId, operatorName);

        MaterialIssue issue = new MaterialIssue();
        issue.setIssueNo(returnNo);
        issue.setOrderId(req.getOrderId());
        issue.setOrderNo(req.getOrderNo());
        issue.setWarehouseId(req.getWarehouseId() == null ? 1L : req.getWarehouseId());
        issue.setIssueType(ISSUE_TYPE_RETURN);
        issue.setStatus(STATUS_CONFIRMED);
        issue.setTotalQuantity(req.getItems().stream()
                .map(i -> i.getQuantity() == null ? BigDecimal.ZERO : i.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        issue.setIssueTime(LocalDateTime.now());
        issue.setOperatorId(operatorId);
        issue.setOperatorName(operatorName);
        issue.setRemark(req.getRemark());
        issueMapper.insert(issue);

        for (var item : req.getItems()) {
            MaterialIssueItem mii = new MaterialIssueItem();
            mii.setIssueId(issue.getId());
            mii.setMaterialId(item.getMaterialId());
            mii.setBatchNo(item.getBatchNo());
            mii.setQuantity(item.getQuantity());
            issueItemMapper.insert(mii);
        }
        log.info("[退料成功] returnNo={} orderId={}", returnNo, req.getOrderId());

        IssueResultVO vo = new IssueResultVO();
        vo.setIssueId(issue.getId());
        vo.setIssueNo(returnNo);
        vo.setOrderId(req.getOrderId());
        vo.setItemCount(req.getItems().size());
        vo.setTotalQuantity(issue.getTotalQuantity());
        vo.setMessage("退料成功");
        return vo;
    }

    public List<MaterialIssue> listByOrder(Long orderId) {
        return issueMapper.selectByOrderId(orderId);
    }

    public List<MaterialIssueItem> listItems(Long issueId) {
        return issueItemMapper.selectByIssueId(issueId);
    }

    /**
     * 按业务单号查流水（对账/追溯用）。
     */
    public List<InventoryTransaction> listTransactionsByBizNo(String bizNo) {
        return transactionMapper.selectByBizNo(bizNo);
    }

    private IssueResultVO idempotentResult(String issueNo) {
        MaterialIssue exist = issueMapper.selectByIssueNo(issueNo);
        IssueResultVO vo = new IssueResultVO();
        if (exist != null) {
            vo.setIssueId(exist.getId());
            vo.setTotalQuantity(exist.getTotalQuantity());
        }
        vo.setIssueNo(issueNo);
        vo.setIdempotent(true);
        vo.setMessage("单号已处理过（幂等返回，未重复处理）");
        return vo;
    }
}
