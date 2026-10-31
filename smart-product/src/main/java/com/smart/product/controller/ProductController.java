package com.smart.product.controller;

import com.smart.common.result.PageResult;
import com.smart.common.result.Result;
import com.smart.product.dto.ProductDtos.MaterialSaveDTO;
import com.smart.product.dto.ProductDtos.ProductSaveDTO;
import com.smart.product.entity.Material;
import com.smart.product.entity.Product;
import com.smart.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品与物料接口（第 2 周任务 2.1 / 2.2）。
 */
@Tag(name = "01-基础资料：产品与物料")
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /* ==================== 产品 ==================== */

    @Operation(summary = "分页查询产品")
    @GetMapping("/product/page")
    public Result<PageResult<Product>> pageProduct(
            @Parameter(description = "页码，从 1 开始") @RequestParam(defaultValue = "1") long current,
            @Parameter(description = "每页条数") @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "编码/名称模糊搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 1-启用 0-停用") @RequestParam(required = false) Integer status) {
        return Result.ok(productService.pageProduct(current, size, keyword, status));
    }

    @Operation(summary = "产品详情")
    @GetMapping("/product/{id}")
    public Result<Product> getProduct(@PathVariable Long id) {
        return Result.ok(productService.getProduct(id));
    }

    @Operation(summary = "按编码查产品")
    @GetMapping("/product/by-code/{code}")
    public Result<Product> getProductByCode(@PathVariable String code) {
        return Result.ok(productService.getProductByCode(code));
    }

    @Operation(summary = "创建产品（编码重复返回友好错误，不抛 500）")
    @PostMapping("/product")
    public Result<Product> createProduct(@Valid @RequestBody ProductSaveDTO dto) {
        return Result.ok(productService.createProduct(dto), "产品创建成功");
    }

    @Operation(summary = "更新产品")
    @PutMapping("/product/{id}")
    public Result<Product> updateProduct(@PathVariable Long id,
                                         @Valid @RequestBody ProductSaveDTO dto) {
        return Result.ok(productService.updateProduct(id, dto), "产品更新成功");
    }

    @Operation(summary = "删除产品（已被 BOM 引用时拒绝）")
    @DeleteMapping("/product/{id}")
    public Result<Void> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        return Result.ok(null, "产品删除成功");
    }

    /* ==================== 物料 ==================== */

    @Operation(summary = "分页查询物料")
    @GetMapping("/material/page")
    public Result<PageResult<Material>> pageMaterial(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "编码/名称模糊搜索") @RequestParam(required = false) String keyword,
            @Parameter(description = "类型 RAW/AUX/PACK") @RequestParam(required = false) String materialType,
            @RequestParam(required = false) Integer status) {
        return Result.ok(productService.pageMaterial(current, size, keyword, materialType, status));
    }

    @Operation(summary = "物料详情")
    @GetMapping("/material/{id}")
    public Result<Material> getMaterial(@PathVariable Long id) {
        return Result.ok(productService.getMaterial(id));
    }

    @Operation(summary = "查询全部物料（下拉选择用）")
    @GetMapping("/material/list")
    public Result<List<Material>> listMaterials() {
        return Result.ok(productService.listMaterials());
    }

    @Operation(summary = "创建物料")
    @PostMapping("/material")
    public Result<Material> createMaterial(@Valid @RequestBody MaterialSaveDTO dto) {
        return Result.ok(productService.createMaterial(dto), "物料创建成功");
    }

    @Operation(summary = "更新物料")
    @PutMapping("/material/{id}")
    public Result<Material> updateMaterial(@PathVariable Long id,
                                           @Valid @RequestBody MaterialSaveDTO dto) {
        return Result.ok(productService.updateMaterial(id, dto), "物料更新成功");
    }

    @Operation(summary = "删除物料（已被 BOM 明细引用时拒绝）")
    @DeleteMapping("/material/{id}")
    public Result<Void> deleteMaterial(@PathVariable Long id) {
        productService.deleteMaterial(id);
        return Result.ok(null, "物料删除成功");
    }
}
