package com.smart.production.service;

import com.smart.production.service.ProductionOrderService.BomInfo;
import com.smart.production.service.ProductionOrderService.BomItemInfo;
import com.smart.production.service.ProductionOrderService.ProductInfo;
import com.smart.production.service.ProductionOrderService.RouteInfo;

import java.util.List;

/**
 * 基础资料服务客户端抽象（依赖倒置）。
 *
 * <h3>为什么抽成接口</h3>
 * 计划书第 7 周："把外部依赖（Feign/MQ）抽成接口，测试时 mock"。
 *
 * <p>生产实现 {@link ProductClient} 走 HTTP 调用 smart-product；
 * 测试实现直接返回构造好的对象，让业务逻辑测试不依赖网络。
 *
 * <p>这也让"以后换成 Feign + LoadBalancer"成为零业务代码改动的替换。
 */
public interface ProductCatalogClient {

    /** 查产品详情（不存在返回 null） */
    ProductInfo getProduct(Long productId);

    /** 查产品当前生效的 BOM（不存在返回 null） */
    BomInfo getActiveBom(Long productId);

    /** 按 bomId 查物料需求素材（含损耗率） */
    List<BomItemInfo> getBomRequirementItems(Long bomId);

    /** 查产品当前生效的工艺路线（含有序工序，不存在返回 null） */
    RouteInfo getActiveRoute(Long productId);
}
