package com.smart.inventory.controller;

import com.smart.common.result.Result;
import com.smart.inventory.dto.InventoryDtos.PurchaseApproveDTO;
import com.smart.inventory.dto.InventoryDtos.PurchaseApproveResultVO;
import com.smart.inventory.dto.InventoryDtos.PurchaseRequestCreateDTO;
import com.smart.inventory.dto.InventoryDtos.PurchaseRequestVO;
import com.smart.inventory.service.PurchaseApprovalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 进货审批单（补料申请）接口。
 *
 * <p>仓管提交 → 管理员 / 计划员或签审批 → 通过自动入库并刷新库存态势。
 * 接口级角色收口见 smart-gateway RoleAuthorizationFilter，服务端再校验一遍。
 */
@Tag(name = "23-进货审批（仓管申请 / 管理员·计划员或签）")
@RestController
@RequestMapping("/purchase")
@RequiredArgsConstructor
public class PurchaseApprovalController {

    private final PurchaseApprovalService purchaseApprovalService;

    @Operation(summary = "提交进货审批单（仅仓库管理员）")
    @PostMapping
    public Result<PurchaseRequestVO> create(@Valid @RequestBody PurchaseRequestCreateDTO dto) {
        return Result.ok(purchaseApprovalService.create(dto), "进货审批单已提交");
    }

    @Operation(summary = "进货审批单列表（按状态筛选，可空）")
    @GetMapping("/list")
    public Result<List<PurchaseRequestVO>> list(@RequestParam(required = false) Integer status) {
        List<PurchaseRequestVO> list = status == null
                ? purchaseApprovalService.listAll()
                : purchaseApprovalService.listByStatus(status);
        return Result.ok(list);
    }

    @Operation(summary = "进货审批单详情")
    @GetMapping("/{id}")
    public Result<PurchaseRequestVO> get(@PathVariable Long id) {
        return Result.ok(purchaseApprovalService.get(id));
    }

    @Operation(summary = "审批通过（或签：管理员或计划员任一人通过即生效，并自动入库）")
    @PostMapping("/{id}/approve")
    public Result<PurchaseApproveResultVO> approve(@PathVariable Long id,
                                                   @RequestBody(required = false) PurchaseApproveDTO dto) {
        return Result.ok(purchaseApprovalService.approve(id, dto));
    }

    @Operation(summary = "驳回（或签：管理员或计划员任一人驳回即终局）")
    @PostMapping("/{id}/reject")
    public Result<PurchaseRequestVO> reject(@PathVariable Long id,
                                            @RequestBody(required = false) PurchaseApproveDTO dto) {
        return Result.ok(purchaseApprovalService.reject(id, dto), "已驳回");
    }

    @Operation(summary = "撤销（仅申请人——仓管——撤回自己的待审批单）")
    @PostMapping("/{id}/cancel")
    public Result<PurchaseRequestVO> cancel(@PathVariable Long id) {
        return Result.ok(purchaseApprovalService.cancel(id), "已撤销");
    }
}
