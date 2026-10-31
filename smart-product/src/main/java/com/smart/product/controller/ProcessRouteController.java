package com.smart.product.controller;

import com.smart.common.result.Result;
import com.smart.product.dto.ProductDtos.RouteCreateDTO;
import com.smart.product.dto.ProductDtos.RouteVO;
import com.smart.product.dto.ProductDtos.StepVO;
import com.smart.product.entity.ProcessRoute;
import com.smart.product.entity.ProcessStep;
import com.smart.product.service.ProcessRouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 工艺路线接口（第 2 周任务 2.5）。
 */
@Tag(name = "03-基础资料：工艺路线（版本化）")
@RestController
@RequiredArgsConstructor
public class ProcessRouteController {

    private final ProcessRouteService routeService;

    @Operation(summary = "查询产品的所有工艺路线版本")
    @GetMapping("/route/product/{productId}")
    public Result<List<ProcessRoute>> listByProduct(@PathVariable Long productId) {
        return Result.ok(routeService.listByProduct(productId));
    }

    @Operation(summary = "查询产品当前生效的工艺路线（含有序工序）")
    @GetMapping("/route/product/{productId}/active")
    public Result<RouteVO> getActiveRoute(@PathVariable Long productId) {
        return Result.ok(routeService.getActiveRouteByProduct(productId));
    }

    @Operation(summary = "工艺路线详情（含有序工序）")
    @GetMapping("/route/{routeId}")
    public Result<RouteVO> getRouteDetail(@PathVariable Long routeId) {
        return Result.ok(routeService.getRouteDetail(routeId));
    }

    @Operation(summary = "查询工艺路线下的有序工序列表",
            description = "按 step_no 升序；is_inspection=1 表示该工序是质检门禁")
    @GetMapping("/route/{routeId}/steps")
    public Result<List<ProcessStep>> listSteps(@PathVariable Long routeId) {
        return Result.ok(routeService.listSteps(routeId));
    }

    @Operation(summary = "创建工艺路线（草稿状态）")
    @PostMapping("/route")
    public Result<RouteVO> createRoute(@Valid @RequestBody RouteCreateDTO dto) {
        return Result.ok(routeService.createRoute(dto), "工艺路线创建成功（草稿）");
    }

    @Operation(summary = "审核生效工艺路线（同产品其他生效版本自动失效）")
    @PostMapping("/route/{routeId}/activate")
    public Result<RouteVO> activateRoute(
            @PathVariable Long routeId,
            @Parameter(description = "生效日期，不传默认今天")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return Result.ok(routeService.activateRoute(routeId, effectiveDate), "工艺路线已生效");
    }

    @Operation(summary = "删除工艺路线（仅草稿可删）")
    @DeleteMapping("/route/{routeId}")
    public Result<Void> deleteRoute(@PathVariable Long routeId) {
        routeService.deleteRoute(routeId);
        return Result.ok(null, "工艺路线已删除");
    }
}
