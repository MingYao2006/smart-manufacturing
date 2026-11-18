package com.smart.inventory.service;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.smart.common.auth.RoleGuard;
import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.inventory.dto.InventoryDtos.PurchaseApproveDTO;
import com.smart.inventory.dto.InventoryDtos.PurchaseApproveResultVO;
import com.smart.inventory.dto.InventoryDtos.PurchaseRequestCreateDTO;
import com.smart.inventory.dto.InventoryDtos.PurchaseRequestVO;
import com.smart.inventory.entity.InventoryTransaction;
import com.smart.inventory.entity.MaterialInventory;
import com.smart.inventory.entity.PurchaseRequest;
import com.smart.inventory.mapper.InventoryTransactionMapper;
import com.smart.inventory.mapper.MaterialInventoryMapper;
import com.smart.inventory.mapper.PurchaseRequestMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 进货审批单（补料申请）服务。
 *
 * <h3>business flow</h3>
 * <ol>
 *   <li>warehouse finds stock insufficient -> submit purchase request ({@link #create})</li>
 *   <li>admin / planner approve (OR-sign: any one is enough) ({@link #approve})</li>
 *   <li>on approve -> auto inbound in same tx {@link InventoryService#applyApprovedInbound}
 *       -> stock quantity / available change -> stock posture refreshes</li>
 * </ol>
 *
 * <h3>concurrency / idempotency</h3>
 * approve uses conditional update {@code UPDATE ... SET status=2 WHERE id=? AND status=1};
 * only the first request can move pending->approved. affected rows = 0 means already
 * processed, rejected. So double-click / concurrent approval only succeeds once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseApprovalService {

    private final PurchaseRequestMapper purchaseRequestMapper;
    private final MaterialInventoryMapper inventoryMapper;
    private final InventoryTransactionMapper transactionMapper;
    private final InventoryService inventoryService;

    /**
     * Create a purchase request. Warehouse only.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseRequestVO create(PurchaseRequestCreateDTO dto) {
        RoleGuard.require("submit purchase request is warehouse only", RoleGuard.WAREHOUSE);

        MaterialInventory inv = inventoryMapper.selectOne(dto.getMaterialId(),
                dto.getWarehouseId() == null ? 1L : dto.getWarehouseId());
        if (inv == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "material[id=" + dto.getMaterialId() + "] has no inventory record");
        }

        PurchaseRequest pr = new PurchaseRequest();
        pr.setRequestNo(BizNoGenerator.generate(BizNoGenerator.PURCHASE_REQUEST_PREFIX));
        pr.setMaterialId(inv.getMaterialId());
        pr.setMaterialCode(inv.getMaterialCode());
        pr.setMaterialName(inv.getMaterialName());
        pr.setUnit(inv.getUnit());
        pr.setWarehouseId(inv.getWarehouseId());
        pr.setQuantity(dto.getQuantity());
        pr.setBatchNo(StringUtils.isBlank(dto.getBatchNo()) ? null : dto.getBatchNo().trim());
        pr.setReason(dto.getReason());
        pr.setSupplierName(dto.getSupplierName());
        pr.setStatus(PurchaseRequest.STATUS_PENDING);
        pr.setApplicantId(RoleGuard.currentUserId());
        pr.setApplicantName(UserContextHolder.currentUserName());
        pr.setApplicantRole(RoleGuard.currentRoleOrNull());
        purchaseRequestMapper.insert(pr);
        log.info("[purchase] created requestNo={} material={} qty={}",
                pr.getRequestNo(), inv.getMaterialCode(), dto.getQuantity());
        return toVO(pr);
    }

    public List<PurchaseRequestVO> listAll() {
        return purchaseRequestMapper.selectAll().stream().map(this::toVO).toList();
    }

    public List<PurchaseRequestVO> listByStatus(int status) {
        return purchaseRequestMapper.selectByStatus(status).stream().map(this::toVO).toList();
    }

    public PurchaseRequestVO get(Long id) {
        PurchaseRequest pr = purchaseRequestMapper.selectById(id);
        if (pr == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "purchase request[id=" + id + "] not found");
        }
        return toVO(pr);
    }

    /**
     * Approve (OR-sign): any one of ADMIN or PLANNER. On success, auto-inbound in same tx.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseApproveResultVO approve(Long id, PurchaseApproveDTO dto) {
        RoleGuard.require("approve purchase is admin or planner only", RoleGuard.ADMIN, RoleGuard.PLANNER);
        PurchaseRequest pr = purchaseRequestMapper.selectById(id);
        if (pr == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "purchase request[id=" + id + "] not found");
        }

        int claimed = purchaseRequestMapper.approvePending(id, RoleGuard.currentUserId(),
                UserContextHolder.currentUserName(), RoleGuard.currentRoleOrNull(),
                dto == null ? null : dto.getComment());
        if (claimed != 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "purchase request already processed");
        }

        var inventory = inventoryService.applyApprovedInbound(
                pr.getMaterialId(), pr.getQuantity(), pr.getBatchNo(), pr.getWarehouseId(),
                RoleGuard.currentUserId(), UserContextHolder.currentUserName(), pr.getRequestNo(),
                "purchase request[" + pr.getRequestNo() + "] approved, auto inbound");

        Long txId = transactionMapper.selectByBizNo(pr.getRequestNo()).stream()
                .map(InventoryTransaction::getId)
                .reduce((first, second) -> second).orElse(null);
        if (txId != null) {
            purchaseRequestMapper.updateInboundTxId(id, txId);
        }

        PurchaseRequest updated = purchaseRequestMapper.selectById(id);
        PurchaseApproveResultVO result = new PurchaseApproveResultVO();
        result.setRequestId(updated.getId());
        result.setRequestNo(updated.getRequestNo());
        result.setStatus(updated.getStatus());
        result.setStatusDesc(PurchaseRequest.statusDesc(updated.getStatus()));
        result.setInboundTxId(txId);
        result.setInventory(inventory);
        result.setMessage(String.format("approved, auto inbound %s %s, stock posture refreshed",
                pr.getQuantity().stripTrailingZeros().toPlainString(), pr.getUnit()));
        log.info("[purchase] approved + inbound requestNo={} material={} qty={} approver={}",
                pr.getRequestNo(), pr.getMaterialCode(), pr.getQuantity(), UserContextHolder.currentUserName());
        return result;
    }

    /**
     * Reject (OR-sign): any one of ADMIN or PLANNER.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseRequestVO reject(Long id, PurchaseApproveDTO dto) {
        RoleGuard.require("reject purchase is admin or planner only", RoleGuard.ADMIN, RoleGuard.PLANNER);
        PurchaseRequest pr = purchaseRequestMapper.selectById(id);
        if (pr == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "purchase request[id=" + id + "] not found");
        }
        int claimed = purchaseRequestMapper.closePending(id, PurchaseRequest.STATUS_REJECTED,
                RoleGuard.currentUserId(), UserContextHolder.currentUserName(), RoleGuard.currentRoleOrNull(),
                dto == null ? null : dto.getComment());
        if (claimed != 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "purchase request already processed");
        }
        log.info("[purchase] rejected requestNo={} approver={}", pr.getRequestNo(), UserContextHolder.currentUserName());
        return toVO(purchaseRequestMapper.selectById(id));
    }

    /**
     * Cancel: only the applicant (warehouse) can withdraw their own pending request.
     */
    @Transactional(rollbackFor = Exception.class)
    public PurchaseRequestVO cancel(Long id) {
        RoleGuard.require("cancel purchase is warehouse only", RoleGuard.WAREHOUSE);
        PurchaseRequest pr = purchaseRequestMapper.selectById(id);
        if (pr == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "purchase request[id=" + id + "] not found");
        }
        Long me = RoleGuard.currentUserId();
        if (me != null && pr.getApplicantId() != null && !pr.getApplicantId().equals(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "can only cancel own purchase request");
        }
        int claimed = purchaseRequestMapper.closePending(id, PurchaseRequest.STATUS_CANCELLED,
                RoleGuard.currentUserId(), UserContextHolder.currentUserName(), RoleGuard.currentRoleOrNull(),
                "applicant cancelled");
        if (claimed != 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "purchase request already processed");
        }
        log.info("[purchase] cancelled requestNo={} applicant={}", pr.getRequestNo(), UserContextHolder.currentUserName());
        return toVO(purchaseRequestMapper.selectById(id));
    }

    private PurchaseRequestVO toVO(PurchaseRequest pr) {
        PurchaseRequestVO vo = new PurchaseRequestVO();
        vo.setId(pr.getId());
        vo.setRequestNo(pr.getRequestNo());
        vo.setMaterialId(pr.getMaterialId());
        vo.setMaterialCode(pr.getMaterialCode());
        vo.setMaterialName(pr.getMaterialName());
        vo.setUnit(pr.getUnit());
        vo.setWarehouseId(pr.getWarehouseId());
        vo.setQuantity(pr.getQuantity());
        vo.setBatchNo(pr.getBatchNo());
        vo.setReason(pr.getReason());
        vo.setSupplierName(pr.getSupplierName());
        vo.setStatus(pr.getStatus());
        vo.setStatusDesc(PurchaseRequest.statusDesc(pr.getStatus()));
        vo.setApplicantId(pr.getApplicantId());
        vo.setApplicantName(pr.getApplicantName());
        vo.setApplicantRole(pr.getApplicantRole());
        vo.setApproverId(pr.getApproverId());
        vo.setApproverName(pr.getApproverName());
        vo.setApproverRole(pr.getApproverRole());
        vo.setApproveComment(pr.getApproveComment());
        vo.setApproveTime(pr.getApproveTime());
        vo.setInboundTxId(pr.getInboundTxId());
        vo.setCreateTime(pr.getCreateTime());
        vo.setUpdateTime(pr.getUpdateTime());
        return vo;
    }
}
