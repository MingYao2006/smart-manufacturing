package com.smart.production.service;

import com.smart.production.dto.ProductionDtos.MaterialRequirementVO;
import com.smart.production.dto.ProductionDtos.PlanCreateDTO;
import com.smart.production.entity.ProductionOrder;
import com.smart.production.entity.ProductionPlan;
import com.smart.production.mapper.ProductionOrderMapper;
import com.smart.production.mapper.ProductionPlanMapper;
import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.util.BizNoGenerator;
import com.smart.production.service.ProductionOrderService.BomItemInfo;
import com.smart.production.service.ProductionOrderService.ProductInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产计划服务 + BOM 物料需求计算（第 3 周任务 3.1 + 第 4 周任务 4.2）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionPlanService {

    private final ProductionPlanMapper planMapper;
    private final ProductionOrderMapper orderMapper;
    private final ProductCatalogClient productClient;

    /** 计划状态 */
    public static final int PLAN_DRAFT = 1;
    public static final int PLAN_CONFIRMED = 2;
    public static final int PLAN_CONVERTED = 3;
    public static final int PLAN_CANCELLED = 4;

    /* ==================== 计划 CRUD ==================== */

    @Transactional(rollbackFor = Exception.class)
    public ProductionPlan createPlan(PlanCreateDTO dto) {
        ProductInfo product = productClient.getProduct(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + dto.getProductId());
        }
        if (dto.getPlanStartDate() != null && dto.getPlanEndDate() != null
                && dto.getPlanEndDate().isBefore(dto.getPlanStartDate())) {
            throw new BusinessException("计划完成日不能早于开始日");
        }

        ProductionPlan plan = new ProductionPlan();
        plan.setPlanNo(BizNoGenerator.generate(BizNoGenerator.PLAN_PREFIX));
        plan.setProductId(product.getId());
        plan.setProductCode(product.getProductCode());
        plan.setProductName(product.getProductName());
        plan.setPlanQuantity(dto.getPlanQuantity());
        plan.setPlanStartDate(dto.getPlanStartDate());
        plan.setPlanEndDate(dto.getPlanEndDate());
        plan.setStatus(PLAN_DRAFT);
        plan.setRemark(dto.getRemark());
        planMapper.insert(plan);
        log.info("[计划创建] planNo={} product={} qty={}",
                plan.getPlanNo(), product.getProductCode(), dto.getPlanQuantity());
        return plan;
    }

    public ProductionPlan getPlan(Long id) {
        ProductionPlan p = planMapper.selectOneById(id);
        if (p == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "生产计划不存在: " + id);
        }
        return p;
    }

    public List<ProductionPlan> listPlans(Integer status) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProductionPlan> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (status != null) {
            w.eq(ProductionPlan::getStatus, status);
        }
        w.orderByDesc(ProductionPlan::getId);
        return planMapper.selectList(w);
    }

    /**
     * 确认计划：草稿 → 已确认（CAS）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductionPlan confirmPlan(Long planId) {
        getPlan(planId);
        int affected = planMapper.updateStatusCas(planId, PLAN_CONFIRMED, PLAN_DRAFT,
                UserContextHolder.currentUserId());
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "计划状态已变更，只有草稿状态的计划可以确认");
        }
        log.info("[计划确认] planId={}", planId);
        return getPlan(planId);
    }

    /**
     * 计划转工单：把计划标记为"已转工单"。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductionPlan markConverted(Long planId, Long orderId) {
        int affected = planMapper.updateStatusCas(planId, PLAN_CONVERTED, PLAN_CONFIRMED,
                UserContextHolder.currentUserId());
        if (affected != 1) {
            throw new BusinessException(ErrorCode.STATE_TRANSITION_DENIED,
                    "只有已确认状态的计划可以转工单");
        }
        log.info("[计划转工单] planId={} orderId={}", planId, orderId);
        return getPlan(planId);
    }

    /* ==================== 🔑 BOM 物料需求计算（任务 4.2） ==================== */

    /**
     * 计算工单的物料需求清单。
     *
     * <p>步骤严格按需求文档 4.2：
     * <pre>
     *  ① 查 bom_item（按【工单锁定的 bomId】，不是产品当前 BOM）
     *  ② 每条明细：需求量 = 单位用量 × 计划数量 × (1 + 损耗率)
     *  ③ 向上取整（物料不能领半个）
     *  ④ 汇总同一物料（BOM 里可能出现同一物料多次）
     *  ⑤ 由库存服务补充可用量并标记充足/短缺（本方法只负责算出"需要多少"）
     * </pre>
     *
     * <p>为什么必须用工单锁定的 bomId：BOM 可能已升级到 V2，
     * 但正在生产的工单必须按 V1 备料，否则物料需求会莫名变化。
     *
     * <p>为什么损耗率是必需的：制造业必有损耗。淘宝"买 1 件"就是 1 件；
     * 生产 1 个产品可能要 1.02 份原料。漏算损耗 = 生产中途缺料停线。
     */
    public List<MaterialRequirementVO> calculateRequirements(ProductionOrder order) {
        return calculateRequirementsByBom(order.getBomId(), order.getPlanQuantity());
    }

    /**
     * 按 bomId + 计划数量计算需求（供工单和"试算"共用）。
     */
    public List<MaterialRequirementVO> calculateRequirementsByBom(Long bomId, BigDecimal planQuantity) {
        // ① 按 bomId 取明细（含损耗率）
        List<BomItemInfo> items = productClient.getBomRequirementItems(bomId);
        if (items == null || items.isEmpty()) {
            throw new BusinessException("BOM[id=" + bomId + "]没有明细，无法计算物料需求");
        }

        // ②③④ 逐行计算 + 按 materialId 汇总
        Map<Long, Agg> aggMap = new LinkedHashMap<>();
        for (BomItemInfo it : items) {
            BigDecimal unitQty = nz(it.getQuantity());
            BigDecimal lossRate = nz(it.getLossRate());
            // 需求量 = 单位用量 × 计划数量 × (1 + 损耗率)
            BigDecimal required = unitQty
                    .multiply(planQuantity)
                    .multiply(BigDecimal.ONE.add(lossRate));
            // 向上取整：物料不能领半个（如算出 100.3 个，必须领 101 个）
            required = required.setScale(0, RoundingMode.CEILING);

            Agg agg = aggMap.computeIfAbsent(it.getMaterialId(), k -> new Agg(it));
            agg.unitQuantity = agg.unitQuantity.add(unitQty);
            agg.requiredQuantity = agg.requiredQuantity.add(required);
        }

        List<MaterialRequirementVO> result = new ArrayList<>(aggMap.size());
        for (Agg agg : aggMap.values()) {
            MaterialRequirementVO vo = new MaterialRequirementVO();
            vo.setMaterialId(agg.item.getMaterialId());
            vo.setMaterialCode(agg.item.getMaterialCode());
            vo.setMaterialName(agg.item.getMaterialName());
            vo.setSpec(null);
            vo.setUnit(agg.item.getUnit());
            vo.setUnitQuantity(agg.unitQuantity);
            vo.setLossRate(agg.lossRate);
            vo.setRequiredQuantity(agg.requiredQuantity);
            result.add(vo);
        }
        result.sort(Comparator.comparing(MaterialRequirementVO::getMaterialId));
        log.debug("[物料需求计算] bomId={} planQty={} 需求项数={}",
                bomId, planQuantity, result.size());
        return result;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 汇总累加器。
     */
    private static final class Agg {
        private final BomItemInfo item;
        private BigDecimal unitQuantity = BigDecimal.ZERO;
        private BigDecimal lossRate;
        private BigDecimal requiredQuantity = BigDecimal.ZERO;

        Agg(BomItemInfo item) {
            this.item = item;
            this.lossRate = item.getLossRate();
        }
    }

    /**
     * 计划下的工单列表。
     */
    public List<ProductionOrder> listOrdersByPlan(Long planId) {
        getPlan(planId);
        return orderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProductionOrder>()
                        .eq(ProductionOrder::getPlanId, planId)
                        .orderByDesc(ProductionOrder::getId));
    }
}
