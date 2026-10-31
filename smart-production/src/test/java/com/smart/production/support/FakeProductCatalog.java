package com.smart.production.support;

import com.smart.production.service.ProductCatalogClient;
import com.smart.production.service.ProductionOrderService.BomInfo;
import com.smart.production.service.ProductionOrderService.BomItemInfo;
import com.smart.production.service.ProductionOrderService.ProductInfo;
import com.smart.production.service.ProductionOrderService.RouteInfo;
import com.smart.production.service.ProductionOrderService.RouteStepInfo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 内存版基础资料客户端（测试用 Fake）。
 *
 * <p>让业务逻辑测试完全不依赖 smart-product 服务/网络。
 * 这是计划书第 7 周要求的做法：把外部依赖抽成接口，测试时用 Fake。
 */
public class FakeProductCatalog implements ProductCatalogClient {

    private ProductInfo product;
    private BomInfo activeBom;
    private RouteInfo activeRoute;
    private final List<BomItemInfo> requirementItems = new ArrayList<>();

    public static FakeProductCatalog empty() {
        return new FakeProductCatalog();
    }

    /** 构造一个"产品启用 + 有生效 BOM + 有生效工艺路线"的正常场景 */
    public static FakeProductCatalog healthy() {
        FakeProductCatalog f = new FakeProductCatalog();

        ProductInfo p = new ProductInfo();
        p.setId(1L);
        p.setProductCode("PRD-DB-X1");
        p.setProductName("智能电表 X1");
        p.setSpec("220V/5(60)A");
        p.setUnit("台");
        p.setStatus(1);
        f.product = p;

        BomInfo bom = new BomInfo();
        bom.setId(1L);
        bom.setProductId(1L);
        bom.setBomVersion("V1");
        bom.setStatus(2);
        bom.setItems(List.of(
                bomItem(1L, "MAT-MCU-01", "计量芯片", "颗", "1", "0.02"),
                bomItem(4L, "MAT-LCD-01", "LCD 屏", "块", "1", "0.03")
        ));
        f.activeBom = bom;

        RouteInfo route = new RouteInfo();
        route.setId(1L);
        route.setProductId(1L);
        route.setRouteVersion("V1");
        route.setStatus(2);
        route.setSteps(List.of(
                step(10, "外壳组装", "装配一车间", "0.25", 0),
                step(20, "老化测试", "测试车间", "0.50", 1),
                step(30, "包装入库", "包装车间", "0.10", 0)
        ));
        f.activeRoute = route;

        f.requirementItems.addAll(bom.getItems());
        return f;
    }

    public static BomItemInfo bomItem(Long materialId, String code, String name,
                                      String unit, String qty, String lossRate) {
        BomItemInfo i = new BomItemInfo();
        i.setMaterialId(materialId);
        i.setMaterialCode(code);
        i.setMaterialName(name);
        i.setUnit(unit);
        i.setQuantity(new BigDecimal(qty));
        i.setLossRate(new BigDecimal(lossRate));
        i.setIsBatchManaged(1);
        return i;
    }

    public static RouteStepInfo step(int stepNo, String name, String workCenter,
                                     String hours, int isInspection) {
        RouteStepInfo s = new RouteStepInfo();
        s.setStepNo(stepNo);
        s.setStepName(name);
        s.setWorkCenter(workCenter);
        s.setStandardHours(new BigDecimal(hours));
        s.setIsInspection(isInspection);
        return s;
    }

    /* ==================== 配置方法 ==================== */

    public FakeProductCatalog withProduct(ProductInfo p) {
        this.product = p;
        return this;
    }

    public FakeProductCatalog withActiveBom(BomInfo b) {
        this.activeBom = b;
        return this;
    }

    public FakeProductCatalog withActiveRoute(RouteInfo r) {
        this.activeRoute = r;
        return this;
    }

    /** 模拟"产品未配置生效 BOM" */
    public FakeProductCatalog withoutBom() {
        this.activeBom = null;
        return this;
    }

    /** 模拟"产品未配置生效工艺路线" */
    public FakeProductCatalog withoutRoute() {
        this.activeRoute = null;
        return this;
    }

    /** 模拟"产品已停用" */
    public FakeProductCatalog withDisabledProduct() {
        if (this.product != null) {
            this.product.setStatus(0);
        }
        return this;
    }

    /* ==================== 接口实现 ==================== */

    @Override
    public ProductInfo getProduct(Long productId) {
        return product;
    }

    @Override
    public BomInfo getActiveBom(Long productId) {
        return activeBom;
    }

    @Override
    public List<BomItemInfo> getBomRequirementItems(Long bomId) {
        return requirementItems;
    }

    @Override
    public RouteInfo getActiveRoute(Long productId) {
        return activeRoute;
    }
}
