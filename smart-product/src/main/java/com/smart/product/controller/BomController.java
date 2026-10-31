package com.smart.product.controller;

import com.smart.common.result.Result;
import com.smart.product.dto.ProductDtos.BomCreateDTO;
import com.smart.product.dto.ProductDtos.BomRequirementItem;
import com.smart.product.dto.ProductDtos.BomTreeNode;
import com.smart.product.dto.ProductDtos.BomVO;
import com.smart.product.entity.Bom;
import com.smart.product.service.BomService;
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
 * BOM 接口（第 2 周任务 2.3 / 2.4）。
 */
@Tag(name = "02-基础资料：BOM 管理（版本化）")
@RestController
@RequiredArgsConstructor
public class BomController {

    private final BomService bomService;

    @Operation(summary = "查询产品的所有 BOM 版本")
    @GetMapping("/bom/product/{productId}")
    public Result<List<Bom>> listByProduct(@PathVariable Long productId) {
        return Result.ok(bomService.listByProduct(productId));
    }

    @Operation(summary = "查询产品当前生效的 BOM（含明细）")
    @GetMapping("/bom/product/{productId}/active")
    public Result<BomVO> getActiveBom(@PathVariable Long productId) {
        Bom bom = bomService.requireActiveBom(productId);
        return Result.ok(bomService.getBomDetail(bom.getId()));
    }

    @Operation(summary = "BOM 详情（含明细）")
    @GetMapping("/bom/{bomId}")
    public Result<BomVO> getBomDetail(@PathVariable Long bomId) {
        return Result.ok(bomService.getBomDetail(bomId));
    }

    @Operation(summary = "创建 BOM（草稿状态）")
    @PostMapping("/bom")
    public Result<BomVO> createBom(@Valid @RequestBody BomCreateDTO dto) {
        return Result.ok(bomService.createBom(dto), "BOM 创建成功（草稿）");
    }

    @Operation(summary = "审核生效 BOM（同产品其他生效版本自动失效）")
    @PostMapping("/bom/{bomId}/activate")
    public Result<BomVO> activateBom(
            @PathVariable Long bomId,
            @Parameter(description = "生效日期，不传默认今天")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate) {
        return Result.ok(bomService.activateBom(bomId, effectiveDate), "BOM 已生效");
    }

    @Operation(summary = "删除 BOM（仅草稿可删）")
    @DeleteMapping("/bom/{bomId}")
    public Result<Void> deleteBom(@PathVariable Long bomId) {
        bomService.deleteBom(bomId);
        return Result.ok(null, "BOM 已删除");
    }

    @Operation(summary = "BOM 多层树展开（含环路检测与深度限制）",
            description = "递归展开产品的多层 BOM；存在 A→B→A 循环引用时返回 BOM_CYCLE_DETECTED(40910)")
    @GetMapping("/bom/{productId}/tree")
    public Result<List<BomTreeNode>> getBomTree(@PathVariable Long productId) {
        return Result.ok(bomService.getBomTree(productId));
    }

    @Operation(summary = "按 bomId 查物料需求素材（供生产/库存服务计算）",
            description = "⚠️ 生产服务必须用【工单锁定的 bomId】调用，而不是产品当前 BOM")
    @GetMapping("/bom/{bomId}/requirement-items")
    public Result<List<BomRequirementItem>> requirementItems(@PathVariable Long bomId) {
        return Result.ok(bomService.listRequirementItems(bomId));
    }
}
