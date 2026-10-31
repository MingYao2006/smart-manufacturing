package com.smart.inventory.service;

import com.smart.common.constant.CommonConstants;
import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.inventory.dto.InventoryDtos.InventoryVO;
import com.smart.inventory.dto.InventoryDtos.IssueItemDTO;
import com.smart.inventory.dto.InventoryDtos.KittingItemVO;
import com.smart.inventory.dto.InventoryDtos.KittingVO;
import com.smart.inventory.dto.InventoryDtos.RequirementItem;
import com.smart.inventory.dto.InventoryDtos.ReservationVO;
import com.smart.inventory.dto.InventoryDtos.ReserveRequest;
import com.smart.inventory.dto.InventoryDtos.ReserveResultVO;
import com.smart.inventory.entity.InventoryTransaction;
import com.smart.inventory.entity.MaterialInventory;
import com.smart.inventory.entity.MaterialReservation;
import com.smart.inventory.mapper.InventoryTransactionMapper;
import com.smart.inventory.mapper.MaterialInventoryMapper;
import com.smart.inventory.mapper.MaterialReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 库存服务（第 4 周）。<b>全项目技术含量最高的一环。</b>
 *
 * <h3>核心：预留与实扣两段式</h3>
 * <pre>
 *   动作        quantity    reserved_quantity   说明
 *   ─────────────────────────────────────────────────────────
 *   入库          +N          不变              采购到货
 *   预留          不变         +N               工单锁料，物料还在仓库
 *   领料          -N          -N               物料离开仓库
 *   释放预留      不变         -N               工单取消
 *   退料          +N          不变              物料退回仓库
 * </pre>
 *
 * <h3>为什么分两段（预留 + 实扣）而不是只有一步</h3>
 * <ul>
 *   <li><b>创建工单就扣</b>：工单取消要退回；长期不生产库存被占死</li>
 *   <li><b>领料时才扣</b>：两个工单同时认为库存够用 → 超发</li>
 *   <li><b>预留 + 实扣</b>：✅ 先锁定再消耗，既防超发又不提前扣账</li>
 * </ul>
 *
 * <h3>并发安全的关键三条</h3>
 * <ol>
 *   <li><b>条件更新</b>：判断与更新压进同一条 SQL（见 MaterialInventoryMapper）</li>
 *   <li><b>排序遍历</b>：多物料按 materialId 升序预留，保证加锁顺序一致 → 防死锁</li>
 *   <li><b>整体回滚</b>：一个工单的多个物料必须在同一事务，第 N 个失败时前面全部回滚</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    /** 流水类型 */
    public static final int TX_INBOUND = 1;
    public static final int TX_OUTBOUND = 2;
    public static final int TX_RESERVE = 3;
    public static final int TX_RELEASE_RESERVE = 4;
    public static final int TX_RETURN = 5;
    public static final int TX_ADJUST = 6;

    private final MaterialInventoryMapper inventoryMapper;
    private final MaterialReservationMapper reservationMapper;
    private final InventoryTransactionMapper transactionMapper;

    /* ==================== 查询 ==================== */

    public InventoryVO getInventory(Long materialId, Long warehouseId) {
        MaterialInventory inv = inventoryMapper.selectOne(materialId, wid(warehouseId));
        if (inv == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "物料[id=" + materialId + "]在该仓库没有库存记录");
        }
        return toVO(inv);
    }

    public List<InventoryVO> listInventory() {
        return inventoryMapper.selectAll().stream().map(this::toVO).toList();
    }

    public List<InventoryVO> listBelowSafetyStock() {
        return inventoryMapper.selectBelowSafetyStock().stream().map(this::toVO).toList();
    }

    public InventoryVO toVO(MaterialInventory inv) {
        InventoryVO vo = new InventoryVO();
        vo.setId(inv.getId());
        vo.setMaterialId(inv.getMaterialId());
        vo.setMaterialCode(inv.getMaterialCode());
        vo.setMaterialName(inv.getMaterialName());
        vo.setUnit(inv.getUnit());
        vo.setWarehouseId(inv.getWarehouseId());
        vo.setQuantity(nz(inv.getQuantity()));
        vo.setReservedQuantity(nz(inv.getReservedQuantity()));
        // 可用量是计算值，永不落库
        vo.setAvailableQuantity(inv.availableQuantity());
        vo.setSafetyStock(nz(inv.getSafetyStock()));
        vo.setBelowSafetyStock(inv.availableQuantity().compareTo(nz(inv.getSafetyStock())) < 0);
        return vo;
    }

    /* ==================== 🔑 物料预留（任务 4.3） ==================== */

    /**
     * 工单物料预留。
     *
     * <h3>步骤</h3>
     * <ol>
     *   <li>幂等：同一工单已预留过则直接返回（{@code uk_order_material} 兜底）</li>
     *   <li><b>按 materialId 升序排序</b>（防死锁）</li>
     *   <li>逐个条件更新预留，影响行数 = 0 立即抛异常</li>
     *   <li>写预留记录 + 流水</li>
     * </ol>
     *
     * <p>⚠️ 整个方法在<b>一个事务</b>内：第 3 个物料失败时，前 2 个的预留自动回滚，
     * 不会出现"部分预留"的脏数据。
     *
     * <p>为什么要排序：事务 A 锁物料 1 再锁物料 2，事务 B 也先锁 1 再锁 2，
     * 两者不会互相等待。若顺序相反（A: 1→2，B: 2→1）就会死锁。
     */
    @Transactional(rollbackFor = Exception.class)
    public ReserveResultVO reserve(ReserveRequest req) {
        Long warehouseId = wid(req.getWarehouseId());

        // 1. 幂等：该工单已预留过
        int existing = reservationMapper.countByOrderId(req.getOrderId());
        if (existing > 0) {
            log.info("[物料预留] 工单已预留过，幂等返回 orderId={} 已有 {} 条",
                    req.getOrderId(), existing);
            ReserveResultVO vo = new ReserveResultVO();
            vo.setOrderId(req.getOrderId());
            vo.setReservedCount(existing);
            vo.setMessage("该工单已预留过物料（幂等返回），如需调整请先释放预留");
            return vo;
        }

        // 2. 合并同一物料（BOM 里可能重复出现）+ 按 materialId 升序排序防死锁
        Map<Long, RequirementItem> merged = new LinkedHashMap<>();
        for (RequirementItem it : req.getItems()) {
            merged.merge(it.getMaterialId(), copy(it), (a, b) -> {
                a.setRequiredQuantity(a.getRequiredQuantity().add(b.getRequiredQuantity()));
                return a;
            });
        }
        List<RequirementItem> sorted = merged.values().stream()
                .sorted(Comparator.comparing(RequirementItem::getMaterialId))
                .toList();

        int expireDays = req.getExpireDays() == null ? 7 : req.getExpireDays();
        LocalDateTime expireTime = LocalDateTime.now().plusDays(expireDays);
        Long operatorId = UserContextHolder.currentUserId();
        String operatorName = UserContextHolder.currentUserName();

        int reservedCount = 0;
        for (RequirementItem item : sorted) {
            // 查预留前的库存快照（用于流水记录 before/after）
            MaterialInventory before = inventoryMapper.selectOne(item.getMaterialId(), warehouseId);
            if (before == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND,
                        "物料[" + materialName(item) + "]没有库存记录，无法预留");
            }

            // 3. 条件更新预留（判断 + 更新同一条 SQL）
            int affected = inventoryMapper.reserveStock(item.getMaterialId(), warehouseId,
                    item.getRequiredQuantity());
            if (affected != 1) {
                // 可用量不足 → 整个事务回滚，已预留的物料自动释放
                BigDecimal available = before.availableQuantity();
                throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH,
                        String.format("物料[%s %s]可用库存不足：需求量 %s %s，可用量 %s %s",
                                item.getMaterialCode(), item.getMaterialName(),
                                item.getRequiredQuantity(), item.getUnit(),
                                available, item.getUnit()));
            }

            // 4. 写预留记录
            MaterialReservation r = new MaterialReservation();
            r.setReservationNo(BizNoGenerator.generateWithNano(BizNoGenerator.RESERVATION_PREFIX));
            r.setOrderId(req.getOrderId());
            r.setOrderNo(req.getOrderNo());
            r.setMaterialId(item.getMaterialId());
            r.setMaterialCode(item.getMaterialCode());
            r.setMaterialName(item.getMaterialName());
            r.setWarehouseId(warehouseId);
            r.setReservedQuantity(item.getRequiredQuantity());
            r.setIssuedQuantity(BigDecimal.ZERO);
            r.setStatus(MaterialReservation.STATUS_RESERVED);
            r.setExpireTime(expireTime);
            reservationMapper.insert(r);

            // 5. 写流水（预留只动 reserved，quantity 不变）
            writeTransaction(item.getMaterialId(), item.getMaterialCode(), warehouseId, null,
                    TX_RESERVE, BigDecimal.ZERO,
                    nz(before.getQuantity()), nz(before.getQuantity()),
                    nz(before.getReservedQuantity()),
                    nz(before.getReservedQuantity()).add(item.getRequiredQuantity()),
                    "预留", req.getOrderNo(), operatorId, operatorName,
                    "工单预留物料");

            reservedCount++;
            log.debug("[预留成功] orderId={} material={} qty={}",
                    req.getOrderId(), item.getMaterialCode(), item.getRequiredQuantity());
        }

        log.info("[物料预留完成] orderId={} 预留 {} 种物料", req.getOrderId(), reservedCount);
        ReserveResultVO vo = new ReserveResultVO();
        vo.setOrderId(req.getOrderId());
        vo.setReservedCount(reservedCount);
        vo.setMessage("预留成功，共 " + reservedCount + " 种物料");
        return vo;
    }

    /**
     * 释放工单的全部预留（工单取消时调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseByOrder(Long orderId) {
        List<MaterialReservation> list = reservationMapper.selectByOrderId(orderId);
        int released = 0;
        for (MaterialReservation r : list) {
            if (r.getStatus() == null
                    || (r.getStatus() != MaterialReservation.STATUS_RESERVED
                    && r.getStatus() != MaterialReservation.STATUS_PARTIAL_ISSUED)) {
                continue;
            }
            BigDecimal toRelease = r.remainingQuantity();
            if (toRelease.compareTo(BigDecimal.ZERO) <= 0) {
                reservationMapper.markReleased(r.getId());
                continue;
            }
            MaterialInventory before = inventoryMapper.selectOne(r.getMaterialId(), r.getWarehouseId());
            int affected = inventoryMapper.releaseStock(r.getMaterialId(), r.getWarehouseId(), toRelease);
            if (affected != 1) {
                log.warn("[释放预留] 条件更新失败（可能已被并发释放） reservationId={}", r.getId());
                continue;
            }
            reservationMapper.markReleased(r.getId());
            writeTransaction(r.getMaterialId(), r.getMaterialCode(), r.getWarehouseId(), null,
                    TX_RELEASE_RESERVE, BigDecimal.ZERO,
                    nz(before.getQuantity()), nz(before.getQuantity()),
                    nz(before.getReservedQuantity()),
                    nz(before.getReservedQuantity()).subtract(toRelease),
                    "释放预留", r.getOrderNo(), UserContextHolder.currentUserId(),
                    UserContextHolder.currentUserName(), "工单取消释放预留");
            released++;
        }
        log.info("[释放预留] orderId={} 释放 {} 条", orderId, released);
        return released;
    }

    /* ==================== 🔑 领料（任务 4.4） ==================== */

    /**
     * 领料：预留转实际消耗。
     *
     * <h3>校验链</h3>
     * <ol>
     *   <li>预留记录存在且状态有效（未释放）</li>
     *   <li>领用量 &lt;= 预留量 - 已领量（不足时拒绝，防止超领）</li>
     *   <li>库存条件更新（quantity 和 reserved 同时减，且都校验 &gt;= qty）</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public void issueItems(Long orderId, String orderNo, Long warehouseId,
                           List<IssueItemDTO> items, String issueNo,
                           Long operatorId, String operatorName) {
        Long wid = wid(warehouseId);
        for (IssueItemDTO item : items) {
            // 1. 查预留记录
            MaterialReservation r = reservationMapper.selectByOrderAndMaterial(orderId, item.getMaterialId());
            if (r == null) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ENOUGH,
                        "物料[id=" + item.getMaterialId() + "]没有预留记录，请先预留物料再领料");
            }
            if (r.getStatus() != null
                    && (r.getStatus() == MaterialReservation.STATUS_RELEASED
                    || r.getStatus() == MaterialReservation.STATUS_ISSUED)) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ENOUGH,
                        "物料[" + r.getMaterialCode() + "]的预留已"
                                + (r.getStatus() == MaterialReservation.STATUS_RELEASED ? "释放" : "全部领完")
                                + "，无法再领料");
            }

            // 2. 校验领用量（此处为快速失败；真正的并发防线是下面的条件更新）
            BigDecimal remaining = r.remainingQuantity();
            if (item.getQuantity().compareTo(remaining) > 0) {
                throw new BusinessException(ErrorCode.RESERVATION_NOT_ENOUGH,
                        String.format("物料[%s]领用量超限：本次 %s，预留剩余 %s",
                                r.getMaterialCode(), item.getQuantity(), remaining));
            }

            MaterialInventory before = inventoryMapper.selectOne(item.getMaterialId(), wid);
            if (before == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "物料库存记录不存在");
            }

            // 3. 库存条件更新（同时减 quantity 和 reserved）
            int affected = inventoryMapper.issueStock(item.getMaterialId(), wid, item.getQuantity());
            if (affected != 1) {
                throw new BusinessException(ErrorCode.STOCK_NOT_ENOUGH,
                        "物料[" + r.getMaterialCode() + "]库存或预留不足，无法领料");
            }

            // 4. 更新预留已领量（条件更新，防并发超领）
            int rAffected = reservationMapper.addIssuedQuantity(r.getId(), item.getQuantity());
            if (rAffected != 1) {
                throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                        "物料[" + r.getMaterialCode() + "]预留已被并发修改，请重试");
            }

            // 5. 写流水
            writeTransaction(item.getMaterialId(), r.getMaterialCode(), wid, item.getBatchNo(),
                    TX_OUTBOUND, item.getQuantity().negate(),
                    nz(before.getQuantity()), nz(before.getQuantity()).subtract(item.getQuantity()),
                    nz(before.getReservedQuantity()),
                    nz(before.getReservedQuantity()).subtract(item.getQuantity()),
                    "领料", issueNo, operatorId, operatorName, "工单领料出库");
        }
    }

    /**
     * 退料：物料退回仓库。
     *
     * <p>账本只增不改，所以退料是记一条<b>方向相反</b>的流水，不是修改原流水。
     */
    @Transactional(rollbackFor = Exception.class)
    public void returnItems(Long orderId, String orderNo, Long warehouseId,
                            List<IssueItemDTO> items, String returnNo,
                            Long operatorId, String operatorName) {
        Long wid = wid(warehouseId);
        for (IssueItemDTO item : items) {
            MaterialInventory before = inventoryMapper.selectOne(item.getMaterialId(), wid);
            if (before == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "物料库存记录不存在");
            }
            int affected = inventoryMapper.returnStock(item.getMaterialId(), wid, item.getQuantity());
            if (affected != 1) {
                throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "退料失败，请重试");
            }
            writeTransaction(item.getMaterialId(), before.getMaterialCode(), wid, item.getBatchNo(),
                    TX_RETURN, item.getQuantity(),
                    nz(before.getQuantity()), nz(before.getQuantity()).add(item.getQuantity()),
                    nz(before.getReservedQuantity()), nz(before.getReservedQuantity()),
                    "退料", returnNo, operatorId, operatorName, "工单退料入库");
        }
    }

    /* ==================== 入库（追溯起点） ==================== */

    /**
     * 物料入库（含批次）。
     */
    @Transactional(rollbackFor = Exception.class)
    public InventoryVO inbound(Long materialId, BigDecimal quantity, String batchNo,
                               Long warehouseId, Long operatorId, String operatorName,
                               String remark) {
        Long wid = wid(warehouseId);
        MaterialInventory before = inventoryMapper.selectOne(materialId, wid);
        if (before == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "物料[id=" + materialId + "]没有库存记录，请先建立库存档案");
        }
        int affected = inventoryMapper.inboundStock(materialId, wid, quantity);
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT, "入库失败，请重试");
        }
        String bn = (batchNo == null || batchNo.isBlank())
                ? BizNoGenerator.generateWithNano(BizNoGenerator.MATERIAL_BATCH_PREFIX) : batchNo;
        writeTransaction(materialId, before.getMaterialCode(), wid, bn,
                TX_INBOUND, quantity,
                nz(before.getQuantity()), nz(before.getQuantity()).add(quantity),
                nz(before.getReservedQuantity()), nz(before.getReservedQuantity()),
                "入库", bn, operatorId, operatorName, remark);
        log.info("[入库] material={} qty={} batch={}", before.getMaterialCode(), quantity, bn);
        return toVO(inventoryMapper.selectOne(materialId, wid));
    }

    /* ==================== 齐套分析（任务 4.2 的库存侧） ==================== */

    /**
     * 齐套分析：比对需求数量与可用量，标记充足/短缺并给出缺口。
     *
     * <p>"只计算和比对，不扣库存" —— 计算与预留分离，职责清晰。
     */
    public KittingVO kitting(Long orderId, List<RequirementItem> requirements, Long warehouseId) {
        Long wid = wid(warehouseId);
        List<Long> ids = requirements.stream().map(RequirementItem::getMaterialId).toList();
        Map<Long, MaterialInventory> invMap = ids.isEmpty() ? Map.of()
                : inventoryMapper.selectByMaterialIds(wid, ids).stream()
                .collect(Collectors.toMap(MaterialInventory::getMaterialId, i -> i));

        List<KittingItemVO> items = new ArrayList<>();
        boolean allEnough = true;
        for (RequirementItem req : requirements) {
            KittingItemVO vo = new KittingItemVO();
            vo.setMaterialId(req.getMaterialId());
            vo.setMaterialCode(req.getMaterialCode());
            vo.setMaterialName(req.getMaterialName());
            vo.setUnit(req.getUnit());
            vo.setRequiredQuantity(req.getRequiredQuantity());
            MaterialInventory inv = invMap.get(req.getMaterialId());
            BigDecimal available = inv == null ? BigDecimal.ZERO : inv.availableQuantity();
            vo.setQuantity(inv == null ? BigDecimal.ZERO : nz(inv.getQuantity()));
            vo.setReservedQuantity(inv == null ? BigDecimal.ZERO : nz(inv.getReservedQuantity()));
            vo.setAvailableQuantity(available);
            boolean enough = available.compareTo(req.getRequiredQuantity()) >= 0;
            vo.setEnough(enough);
            vo.setShortage(enough ? BigDecimal.ZERO : req.getRequiredQuantity().subtract(available));
            if (!enough) {
                allEnough = false;
            }
            items.add(vo);
        }
        KittingVO result = new KittingVO();
        result.setOrderId(orderId);
        result.setEnough(allEnough);
        result.setItems(items);
        return result;
    }

    /* ==================== 预留查询 ==================== */

    public List<ReservationVO> listReservationsByOrder(Long orderId) {
        return reservationMapper.selectByOrderId(orderId).stream().map(r -> {
            ReservationVO vo = new ReservationVO();
            vo.setId(r.getId());
            vo.setReservationNo(r.getReservationNo());
            vo.setOrderId(r.getOrderId());
            vo.setOrderNo(r.getOrderNo());
            vo.setMaterialId(r.getMaterialId());
            vo.setMaterialCode(r.getMaterialCode());
            vo.setMaterialName(r.getMaterialName());
            vo.setReservedQuantity(nz(r.getReservedQuantity()));
            vo.setIssuedQuantity(nz(r.getIssuedQuantity()));
            vo.setRemainingQuantity(r.remainingQuantity());
            vo.setStatus(r.getStatus());
            vo.setStatusDesc(reservationStatusDesc(r.getStatus()));
            vo.setExpireTime(r.getExpireTime());
            return vo;
        }).toList();
    }

    public static String reservationStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case MaterialReservation.STATUS_RESERVED -> "已预留";
            case MaterialReservation.STATUS_PARTIAL_ISSUED -> "部分领用";
            case MaterialReservation.STATUS_ISSUED -> "全部领用";
            case MaterialReservation.STATUS_RELEASED -> "已释放";
            default -> "未知(" + status + ")";
        };
    }

    /* ==================== 内部工具 ==================== */

    /**
     * 写库存流水（业务与流水必须在同一事务）。
     */
    private void writeTransaction(Long materialId, String materialCode, Long warehouseId,
                                  String batchNo, int type, BigDecimal qty,
                                  BigDecimal beforeQty, BigDecimal afterQty,
                                  BigDecimal beforeReserved, BigDecimal afterReserved,
                                  String bizType, String bizNo, Long operatorId,
                                  String operatorName, String remark) {
        InventoryTransaction tx = new InventoryTransaction();
        tx.setTransactionNo(BizNoGenerator.generateWithNano(BizNoGenerator.TRANSACTION_PREFIX));
        tx.setMaterialId(materialId);
        tx.setMaterialCode(materialCode);
        tx.setWarehouseId(warehouseId);
        tx.setBatchNo(batchNo);
        tx.setTransactionType(type);
        tx.setQuantity(qty);
        tx.setBeforeQuantity(beforeQty);
        tx.setAfterQuantity(afterQty);
        tx.setBeforeReserved(beforeReserved);
        tx.setAfterReserved(afterReserved);
        tx.setBizType(bizType);
        tx.setBizNo(bizNo);
        tx.setOperatorId(operatorId);
        tx.setOperatorName(operatorName);
        tx.setRemark(remark);
        transactionMapper.insert(tx);
    }

    private static Long wid(Long warehouseId) {
        return warehouseId == null ? CommonConstants.DEFAULT_WAREHOUSE_ID : warehouseId;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String materialName(RequirementItem it) {
        return it.getMaterialCode() == null ? String.valueOf(it.getMaterialId()) : it.getMaterialCode();
    }

    private static RequirementItem copy(RequirementItem src) {
        RequirementItem it = new RequirementItem();
        it.setMaterialId(src.getMaterialId());
        it.setMaterialCode(src.getMaterialCode());
        it.setMaterialName(src.getMaterialName());
        it.setUnit(src.getUnit());
        it.setRequiredQuantity(src.getRequiredQuantity());
        it.setIsBatchManaged(src.getIsBatchManaged());
        return it;
    }
}
