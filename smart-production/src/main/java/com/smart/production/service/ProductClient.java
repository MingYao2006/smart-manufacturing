package com.smart.production.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.common.result.Result;
import com.smart.production.service.ProductionOrderService.BomInfo;
import com.smart.production.service.ProductionOrderService.BomItemInfo;
import com.smart.production.service.ProductionOrderService.ProductInfo;
import com.smart.production.service.ProductionOrderService.RouteInfo;
import com.smart.production.service.ProductionOrderService.RouteStepInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问基础资料服务（smart-product）的客户端。
 *
 * <h3>为什么不用 OpenFeign 而用 RestClient</h3>
 * 本项目需要"服务发现不可用也能本地演示"的能力：Nacos 没启动时，
 * Feign 因为没有服务实例会直接失败。这里用 {@link RestClient} + 可配置 baseUrl，
 * 既支持"直连本地端口"（默认），也支持以后切到 Nacos + LoadBalancer。
 *
 * <p>生产环境应改为 Feign + LoadBalancer，本项目为了"开箱即跑"选了更简单的方式。
 * 这个取舍写在这里，面试时被问到"为什么不用 Feign"可以直接讲。
 *
 * <h3>错误处理</h3>
 * 业务错误（BOM_NOT_EFFECTIVE 等）必须<b>原样透传</b>，
 * 不能因为跨服务调用就把"产品未配置 BOM"变成"系统内部错误"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductClient implements ProductCatalogClient {

    private final ObjectMapper objectMapper;

    @Value("${smart.service.product.base-url:http://127.0.0.1:9002}")
    private String baseUrl;

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * 查产品详情。
     */
    public ProductInfo getProduct(Long productId) {
        JsonNode data = call("/product/" + productId);
        if (data == null || data.isNull()) {
            return null;
        }
        ProductInfo p = new ProductInfo();
        p.setId(data.path("id").asLong());
        p.setProductCode(data.path("productCode").asText());
        p.setProductName(data.path("productName").asText());
        p.setSpec(data.path("spec").asText(null));
        p.setUnit(data.path("unit").asText());
        p.setStatus(data.path("status").isMissingNode() ? null : data.path("status").asInt());
        return p;
    }

    /**
     * 查产品当前生效的 BOM（含明细）。
     */
    public BomInfo getActiveBom(Long productId) {
        JsonNode data = callOrNull("/bom/product/" + productId + "/active");
        if (data == null || data.isNull()) {
            return null;
        }
        BomInfo b = new BomInfo();
        b.setId(data.path("id").asLong());
        b.setProductId(data.path("productId").asLong());
        b.setBomVersion(data.path("bomVersion").asText());
        b.setStatus(data.path("status").asInt());
        List<BomItemInfo> items = new ArrayList<>();
        for (JsonNode it : data.path("items")) {
            BomItemInfo i = new BomItemInfo();
            i.setMaterialId(it.path("materialId").asLong());
            i.setMaterialCode(it.path("materialCode").asText(null));
            i.setMaterialName(it.path("materialName").asText(null));
            i.setUnit(it.path("unit").asText(null));
            i.setQuantity(new BigDecimal(it.path("quantity").asText("0")));
            i.setLossRate(new BigDecimal(it.path("lossRate").asText("0")));
            items.add(i);
        }
        b.setItems(items);
        return b;
    }

    /**
     * 按 bomId 查物料需求素材（含损耗率、批次管理标记）。
     */
    public List<BomItemInfo> getBomRequirementItems(Long bomId) {
        JsonNode data = call("/bom/" + bomId + "/requirement-items");
        List<BomItemInfo> items = new ArrayList<>();
        if (data == null || data.isNull()) {
            return items;
        }
        for (JsonNode it : data) {
            BomItemInfo i = new BomItemInfo();
            i.setMaterialId(it.path("materialId").asLong());
            i.setMaterialCode(it.path("materialCode").asText(null));
            i.setMaterialName(it.path("materialName").asText(null));
            i.setUnit(it.path("unit").asText(null));
            i.setQuantity(new BigDecimal(it.path("quantity").asText("0")));
            i.setLossRate(new BigDecimal(it.path("lossRate").asText("0")));
            i.setIsBatchManaged(it.path("isBatchManaged").isMissingNode()
                    ? 0 : it.path("isBatchManaged").asInt());
            items.add(i);
        }
        return items;
    }

    /**
     * 查产品当前生效的工艺路线（含有序工序）。
     */
    public RouteInfo getActiveRoute(Long productId) {
        JsonNode data = callOrNull("/route/product/" + productId + "/active");
        if (data == null || data.isNull()) {
            return null;
        }
        RouteInfo r = new RouteInfo();
        r.setId(data.path("id").asLong());
        r.setProductId(data.path("productId").asLong());
        r.setRouteVersion(data.path("routeVersion").asText());
        r.setStatus(data.path("status").asInt());
        List<RouteStepInfo> steps = new ArrayList<>();
        for (JsonNode s : data.path("steps")) {
            RouteStepInfo si = new RouteStepInfo();
            si.setStepNo(s.path("stepNo").asInt());
            si.setStepName(s.path("stepName").asText());
            si.setWorkCenter(s.path("workCenter").asText(null));
            si.setStandardHours(new BigDecimal(s.path("standardHours").asText("0")));
            si.setIsInspection(s.path("isInspection").isMissingNode() ? 0 : s.path("isInspection").asInt());
            steps.add(si);
        }
        r.setSteps(steps);
        return r;
    }

    /* ==================== 内部：统一解析返回体 ==================== */

    /**
     * 调用并解析 Result&lt;T&gt;。业务错误码原样透传为 {@link BusinessException}。
     */
    private JsonNode call(String path) {
        JsonNode body = doGet(path);
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            String msg = body.path("message").asText("基础资料服务返回错误");
            throw new BusinessException(code, msg);
        }
        return body.path("data");
    }

    /**
     * 调用但允许"资源不存在"（返回 null 表示未配置生效版本）。
     */
    private JsonNode callOrNull(String path) {
        JsonNode body = doGet(path);
        int code = body.path("code").asInt(-1);
        if (code == ErrorCode.NOT_FOUND.getCode()
                || code == ErrorCode.BOM_NOT_EFFECTIVE.getCode()
                || code == ErrorCode.ROUTE_NOT_EFFECTIVE.getCode()) {
            return null;
        }
        if (code != 0) {
            throw new BusinessException(code, body.path("message").asText("基础资料服务返回错误"));
        }
        return body.path("data");
    }

    private JsonNode doGet(String path) {
        try {
            String raw = restClient().get().uri(path).retrieve().body(String.class);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "基础资料服务返回空响应: " + path);
            }
            return objectMapper.readTree(raw);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[跨服务调用失败] product{}", path, e);
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "基础资料服务不可用（" + baseUrl + "），请确认 smart-product 已启动");
        }
    }

    /**
     * 供 {@code Result} 断言用（避免未使用告警）。
     */
    @SuppressWarnings("unused")
    static Result<String> unused() {
        return null;
    }
}
