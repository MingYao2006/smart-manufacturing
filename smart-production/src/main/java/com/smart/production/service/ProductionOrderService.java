package com.smart.production.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smart.common.context.UserContextHolder;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.result.PageResult;
import com.smart.common.util.BizNoGenerator;
import com.smart.production.dto.ProductionDtos.OrderCreateDTO;
import com.smart.production.dto.ProductionDtos.OrderDetailVO;
import com.smart.production.dto.ProductionDtos.OrderVO;
import com.smart.production.dto.ProductionDtos.StatusLogVO;
import com.smart.production.dto.ProductionDtos.WorkTaskVO;
import com.smart.production.entity.OrderStatusLog;
import com.smart.production.entity.ProductionOrder;
import com.smart.production.entity.ProductionPlan;
import com.smart.production.entity.WorkTask;
import com.smart.production.mapper.OrderStatusLogMapper;
import com.smart.production.mapper.ProductionOrderMapper;
import com.smart.production.mapper.ProductionPlanMapper;
import com.smart.production.mapper.WorkTaskMapper;
import com.smart.production.statemachine.ProductionOrderStatus;
import com.smart.production.statemachine.WorkTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 生产工单服务（第 3 周）。整个项目的"中枢"。
 *
 * <h3>关键设计一：快照（任务 3.2）</h3>
 * 创建工单时必须写入 {@code bomId} 与 {@code routeId}，而不只是 productId。
 * 之后 BOM 升级到 V2，正在生产的工单仍用 V1 —— 这是制造业铁律。
 *
 * <h3>关键设计二：统一状态流转入口 + CAS（任务 3.4）</h3>
 * 所有状态变更都走 {@link #transit}：
 * <ol>
 *   <li>查当前状态</li>
 *   <li>内存校验迁移合法性（快速失败）</li>
 *   <li>数据库 CAS 条件更新（并发防线），影响行数必须 = 1</li>
 *   <li>写状态流转日志（同一事务）</li>
 *   <li>处理副作用（生成工序任务、记录实际时间…）</li>
 * </ol>
 *
 * <p>为什么必须 CAS：工单状态会被多个入口并发修改（人工操作、报工消息、定时任务）。
 * 先查后改必然覆盖。与电商项目订单状态踩的坑完全一样。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductionOrderService {

    private final ProductionOrderMapper orderMapper;
    private final ProductionPlanMapper planMapper;
    private final WorkTaskMapper workTaskMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ProductCatalogClient productClient;
    private final WorkTaskService workTaskService;

    /* ==================== 创建工单（含快照） ==================== */

    /**
     * 创建工单。步骤严格按需求文档 3.2：
     * <pre>
     *  ① 校验产品存在且启用
     *  ② 查询该产品当前 ACTIVE 的 BOM 和工艺路线
     *  ③ 若没有生效版本 → 抛业务异常"产品未配置生效BOM/工艺路线"
     *  ④ 把 bom_id、route_id 写入工单（快照）
     *  ⑤ 校验计划数量 > 0、交期合理
     *  ⑥ 生成工单号 WO + yyyyMMdd + 4位序列
     *  ⑦ 插入工单，状态 = DRAFT
     * </pre>
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderCreateDTO dto) {
        // ① 产品存在且启用（跨服务 Feign）
        ProductInfo product = productClient.getProduct(dto.getProductId());
        if (product == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "产品不存在: " + dto.getProductId());
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BusinessException("产品[" + product.getProductCode() + "]已停用，不允许创建工单");
        }

        // ② 生效 BOM 与工艺路线
        BomInfo bom = productClient.getActiveBom(dto.getProductId());
        if (bom == null) {
            throw new BusinessException(ErrorCode.BOM_NOT_EFFECTIVE,
                    "产品[" + product.getProductCode() + "]未配置生效的 BOM，请先创建并审核生效");
        }
        RouteInfo route = productClient.getActiveRoute(dto.getProductId());
        if (route == null) {
            throw new BusinessException(ErrorCode.ROUTE_NOT_EFFECTIVE,
                    "产品[" + product.getProductCode() + "]未配置生效的工艺路线，请先创建并审核生效");
        }

        // ⑤ 数量与交期
        if (!BizNoGenerator.gtZero(dto.getPlanQuantity())) {
            throw new BusinessException("计划数量必须大于 0");
        }
        if (dto.getPlanEndTime() != null && dto.getPlanStartTime() != null
                && dto.getPlanEndTime().isBefore(dto.getPlanStartTime())) {
            throw new BusinessException("交期不能早于计划开始时间");
        }

        // 来源计划校验
        if (dto.getPlanId() != null) {
            ProductionPlan plan = planMapper.selectOneById(dto.getPlanId());
            if (plan == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "来源计划不存在: " + dto.getPlanId());
            }
            if (plan.getProductId() != null && !plan.getProductId().equals(dto.getProductId())) {
                throw new BusinessException("工单产品与来源计划的产品不一致");
            }
        }

        // ⑥⑦ 组装并插入
        ProductionOrder order = new ProductionOrder();
        order.setOrderNo(BizNoGenerator.generate(BizNoGenerator.ORDER_PREFIX));
        order.setPlanId(dto.getPlanId());
        order.setProductId(product.getId());
        order.setProductCode(product.getProductCode());
        order.setProductName(product.getProductName());
        order.setSpec(product.getSpec());
        order.setUnit(product.getUnit());
        // 🔑 快照：锁定 BOM / 工艺路线版本
        order.setBomId(bom.getId());
        order.setBomVersion(bom.getBomVersion());
        order.setRouteId(route.getId());
        order.setRouteVersion(route.getRouteVersion());
        order.setPlanQuantity(dto.getPlanQuantity());
        order.setCompletedQuantity(BigDecimal.ZERO);
        order.setQualifiedQuantity(BigDecimal.ZERO);
        order.setDefectQuantity(BigDecimal.ZERO);
        order.setProgress(0);
        order.setStatus(ProductionOrderStatus.DRAFT.getCode());
        order.setPlanStartTime(dto.getPlanStartTime());
        order.setPlanEndTime(dto.getPlanEndTime());
        order.setRemark(dto.getRemark());

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 工单号重复（多实例下内存序列可能撞车）：唯一索引是最后防线
            throw new BusinessException(ErrorCode.DUPLICATE_KEY, "工单号重复，请重试");
        }

        // 记录创建日志
        statusLogMapper.insert(OrderStatusLog.of(order.getId(), order.getOrderNo(),
                null, ProductionOrderStatus.DRAFT, "创建工单",
                UserContextHolder.currentUserId(), UserContextHolder.currentUserName()));

        log.info("[工单创建] orderNo={} product={} qty={} bomId={}({}) routeId={}({})",
                order.getOrderNo(), product.getProductCode(), dto.getPlanQuantity(),
                bom.getId(), bom.getBomVersion(), route.getId(), route.getRouteVersion());
        return toVO(order);
    }

    /* ==================== 统一状态流转入口（任务 3.4） ==================== */

    /**
     * 工单状态流转的<b>唯一入口</b>。
     *
     * <p>所有状态变更（审核、下达、暂停、取消…）必须走这里，
     * 日志、校验、副作用才不会漏。
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO transit(Long orderId, ProductionOrderStatus target, String reason) {
        // 1. 查当前状态
        ProductionOrder order = getOrderEntity(orderId);
        ProductionOrderStatus current = ProductionOrderStatus.of(order.getStatus());

        // 2. 内存校验迁移合法性（快速失败）
        current.checkTransitTo(target);

        Long operatorId = UserContextHolder.currentUserId();
        String operatorName = UserContextHolder.currentUserName();

        // 3. CAS 更新（数据库兜底，防并发）
        int affected = orderMapper.updateStatusCas(orderId, target.getCode(),
                current.getCode(), operatorId);
        if (affected != 1) {
            // 影响行数 0 → 状态已被其他线程改过
            throw new BusinessException(ErrorCode.CONCURRENT_CONFLICT,
                    "工单状态已变更（当前可能已不是[" + current.getDesc() + "]），请刷新后重试");
        }

        // 4. 记录状态流转日志（同一事务）
        statusLogMapper.insert(OrderStatusLog.of(orderId, order.getOrderNo(),
                current, target, reason, operatorId, operatorName));

        // 5. 处理副作用（同一事务内）
        handleSideEffects(order, current, target);

        log.info("[工单流转] orderNo={} {} → {} reason={} operator={}",
                order.getOrderNo(), current.getDesc(), target.getDesc(), reason, operatorName);
        return toVO(getOrderEntity(orderId));
    }

    /**
     * 副作用集中处理。
     *
     * <p>⚠️ 这是一个"为了用 MQ 而用 MQ"的判断点（需求文档 3.5）：
     * 下达工单时生成工序任务<b>同步做</b>而不是异步。
     * 理由：工序数量少（3~8 道），生成很快，同步完全够用；
     * 而且下达是低频操作，没有解耦收益。MQ 应该用在真正高频的"报工 → 更新进度"上。
     */
    private void handleSideEffects(ProductionOrder order, ProductionOrderStatus from,
                                   ProductionOrderStatus target) {
        switch (target) {
            case RELEASED -> {
                // 下达：按快照 routeId 展开工序任务（同步、幂等）
                int created = workTaskService.generateTasks(order);
                log.info("[工单下达] orderNo={} 生成工序任务 {} 条", order.getOrderNo(), created);
            }
            case IN_PROGRESS -> {
                // 生产中：记录实际开始时间（首次）
                orderMapper.markActualStart(order.getId());
            }
            case COMPLETED -> orderMapper.markActualEnd(order.getId());
            case CANCELLED -> {
                // 取消：释放该工单的物料预留（跨服务，失败不影响工单取消，
                // 由预留过期定时任务兜底）
                try {
                    workTaskService.cancelTasks(order);
                } catch (Exception e) {
                    log.warn("[工单取消] 作废工序任务失败，已忽略 orderNo={}", order.getOrderNo(), e);
                }
            }
            default -> {
                // CLOSED / PAUSED / APPROVED 无副作用
            }
        }
    }

    /**
     * 便捷方法：审核通过。
     */
    public OrderVO approve(Long orderId, String reason) {
        return transit(orderId, ProductionOrderStatus.APPROVED, reason == null ? "审核通过" : reason);
    }

    /**
     * 便捷方法：下达（会生成工序任务）。
     */
    public OrderVO release(Long orderId, String reason) {
        return transit(orderId, ProductionOrderStatus.RELEASED, reason == null ? "工单下达" : reason);
    }

    public OrderVO cancel(Long orderId, String reason) {
        return transit(orderId, ProductionOrderStatus.CANCELLED, reason == null ? "工单取消" : reason);
    }

    /* ==================== 查询 ==================== */

    public ProductionOrder getOrderEntity(Long id) {
        ProductionOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在: " + id);
        }
        return order;
    }

    public PageResult<OrderVO> pageOrder(long current, long size, String keyword,
                                         Integer status, Long productId) {
        Page<ProductionOrder> page = new Page<>(current, size);
        LambdaQueryWrapper<ProductionOrder> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            w.and(x -> x.like(ProductionOrder::getOrderNo, keyword)
                    .or().like(ProductionOrder::getProductName, keyword)
                    .or().like(ProductionOrder::getProductCode, keyword));
        }
        if (status != null) {
            w.eq(ProductionOrder::getStatus, status);
        }
        if (productId != null) {
            w.eq(ProductionOrder::getProductId, productId);
        }
        w.orderByDesc(ProductionOrder::getId);
        Page<ProductionOrder> r = orderMapper.selectPage(page, w);
        List<OrderVO> vos = r.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(vos, r.getTotal(), current, size);
    }

    public OrderDetailVO getOrderDetail(Long id) {
        ProductionOrder order = getOrderEntity(id);
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrder(toVO(order));
        vo.setTasks(workTaskMapper.selectByOrderId(id).stream()
                .map(workTaskService::toVO).toList());
        List<StatusLogVO> logs = new ArrayList<>();
        for (OrderStatusLog l : statusLogMapper.selectByOrderId(id)) {
            StatusLogVO lv = new StatusLogVO();
            lv.setFromStatus(l.getFromStatus());
            lv.setFromStatusDesc(l.getFromStatusDesc());
            lv.setToStatus(l.getToStatus());
            lv.setToStatusDesc(l.getToStatusDesc());
            lv.setReason(l.getReason());
            lv.setOperatorName(l.getOperatorName());
            lv.setOperateTime(l.getOperateTime());
            logs.add(lv);
        }
        vo.setStatusLogs(logs);
        return vo;
    }

    public OrderVO toVO(ProductionOrder o) {
        OrderVO v = new OrderVO();
        v.setId(o.getId());
        v.setOrderNo(o.getOrderNo());
        v.setPlanId(o.getPlanId());
        v.setProductId(o.getProductId());
        v.setProductCode(o.getProductCode());
        v.setProductName(o.getProductName());
        v.setSpec(o.getSpec());
        v.setUnit(o.getUnit());
        v.setBomId(o.getBomId());
        v.setBomVersion(o.getBomVersion());
        v.setRouteId(o.getRouteId());
        v.setRouteVersion(o.getRouteVersion());
        v.setPlanQuantity(o.getPlanQuantity());
        v.setCompletedQuantity(o.getCompletedQuantity());
        v.setQualifiedQuantity(o.getQualifiedQuantity());
        v.setDefectQuantity(o.getDefectQuantity());
        v.setProgress(o.getProgress());
        v.setStatus(o.getStatus());
        v.setStatusDesc(ProductionOrderStatus.descOf(o.getStatus()));
        v.setPlanStartTime(o.getPlanStartTime());
        v.setPlanEndTime(o.getPlanEndTime());
        v.setActualStartTime(o.getActualStartTime());
        v.setActualEndTime(o.getActualEndTime());
        v.setRemark(o.getRemark());
        v.setCreateTime(o.getCreateTime());
        return v;
    }

    /* ==================== 进度重算（幂等） ==================== */

    /**
     * 🔑 重算工单进度 —— 幂等运算，重复执行结果相同。
     *
     * <p>为什么重算而不是累加：
     * <pre>
     *   ❌ 累加：order.completed += qty   → 重复消费就多加一次，进度超 100%
     *   ✅ 重算：order.completed = SUM(task.reported)  → 算几次都一样
     * </pre>
     * 消息是 at-least-once 语义，天然幂等的重算比"去重"更简单也更可靠。
     *
     * <p>同时做"完工判定"：所有工序都到终态（COMPLETED/SKIPPED）时工单转 COMPLETED。
     * 这个判定放在重算里，所以 MQ 消息丢了也能由定时任务兜底触发。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recalculateProgress(Long orderId) {
        ProductionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("[进度重算] 工单不存在 orderId={}", orderId);
            return;
        }
        int total = workTaskMapper.countByOrderId(orderId);
        if (total == 0) {
            return;
        }
        int finished = workTaskMapper.countFinishedByOrderId(orderId);

        BigDecimal reported = nz(workTaskMapper.sumReportedQuantity(orderId));
        BigDecimal qualified = nz(workTaskMapper.sumQualifiedQuantity(orderId));
        BigDecimal defect = nz(workTaskMapper.sumDefectQuantity(orderId));

        int progress = (int) Math.round(finished * 100.0 / total);
        orderMapper.recalcProgress(orderId, reported, qualified, defect, progress);

        // 完工判定：所有工序终态 → 工单完工
        ProductionOrderStatus current = ProductionOrderStatus.of(order.getStatus());
        if (finished == total && current == ProductionOrderStatus.IN_PROGRESS) {
            try {
                transit(orderId, ProductionOrderStatus.COMPLETED, "所有工序已完成，自动完工");
                log.info("[工单完工] orderNo={} 进度=100%", order.getOrderNo());
            } catch (BusinessException e) {
                // 并发下可能已被别的线程完工，忽略
                log.info("[工单完工] 跳过（状态已变更） orderNo={} msg={}", order.getOrderNo(), e.getMessage());
            }
        }
        log.debug("[进度重算] orderNo={} finished={}/{} progress={}% reported={}",
                order.getOrderNo(), finished, total, progress, reported);
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 兜底扫描：所有工序已完成但工单未完工的异常数据。
     *
     * <p>"工单完工判定不能只靠 MQ" —— MQ 丢消息时工单会永远卡在"生产中"。
     * 定时任务扫这些数据并重算，是必要兜底。
     */
    public int repairStuckOrders() {
        List<ProductionOrder> stuck = orderMapper.selectAllTasksCompletedButOrderNot();
        for (ProductionOrder o : stuck) {
            log.warn("[兜底修复] 发现工序全完成但工单未完工 orderNo={}", o.getOrderNo());
            recalculateProgress(o.getId());
        }
        return stuck.size();
    }

    /**
     * 看板统计：各状态工单数。
     */
    public List<java.util.Map<String, Object>> statusStats() {
        List<java.util.Map<String, Object>> raw = orderMapper.countByStatus();
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        for (java.util.Map<String, Object> row : raw) {
            Integer code = ((Number) row.get("status")).intValue();
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("status", code);
            m.put("statusDesc", ProductionOrderStatus.descOf(code));
            m.put("count", ((Number) row.get("cnt")).longValue());
            result.add(m);
        }
        return result;
    }

    /* ==================== 跨服务信息（Feign 返回体） ==================== */

    @lombok.Data
    public static class ProductInfo {
        private Long id;
        private String productCode;
        private String productName;
        private String spec;
        private String unit;
        private Integer status;
    }

    @lombok.Data
    public static class BomInfo {
        private Long id;
        private Long productId;
        private String bomVersion;
        private Integer status;
        private List<BomItemInfo> items;
    }

    @lombok.Data
    public static class BomItemInfo {
        private Long materialId;
        private String materialCode;
        private String materialName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal lossRate;
        private Integer isBatchManaged;
    }

    @lombok.Data
    public static class RouteInfo {
        private Long id;
        private Long productId;
        private String routeVersion;
        private Integer status;
        private List<RouteStepInfo> steps;
    }

    @lombok.Data
    public static class RouteStepInfo {
        private Integer stepNo;
        private String stepName;
        private String workCenter;
        private BigDecimal standardHours;
        private Integer isInspection;
    }
}
