package com.smart.inventory.controller;

import com.smart.common.result.Result;
import com.smart.inventory.dto.InventoryDtos.IssueRequest;
import com.smart.inventory.dto.InventoryDtos.IssueResultVO;
import com.smart.inventory.dto.InventoryDtos.ReturnRequest;
import com.smart.inventory.entity.InventoryTransaction;
import com.smart.inventory.entity.MaterialIssue;
import com.smart.inventory.entity.MaterialIssueItem;
import com.smart.inventory.service.IssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 领料 / 退料 / 库存流水接口（第 4 周任务 4.4 / 4.5）。
 */
@Tag(name = "22-领料与库存流水")
@RestController
@RequiredArgsConstructor
public class IssueController {

    private final IssueService issueService;

    @Operation(summary = "🔑 领料（预留转实际消耗）",
            description = "校验领用量<=预留剩余；库存 quantity 与 reserved 同时减；issueNo 幂等；明细记录批次号")
    @PostMapping("/issue")
    public Result<IssueResultVO> issue(@Valid @RequestBody IssueRequest req) {
        return Result.ok(issueService.issue(req), "领料成功");
    }

    @Operation(summary = "退料（物料退回仓库，记反向流水，不改历史账本）")
    @PostMapping("/issue/return")
    public Result<IssueResultVO> returnMaterial(@Valid @RequestBody ReturnRequest req) {
        return Result.ok(issueService.returnMaterial(req), "退料成功");
    }

    @Operation(summary = "查询工单的领料/退料单")
    @GetMapping("/issue/order/{orderId}")
    public Result<List<MaterialIssue>> listByOrder(@PathVariable Long orderId) {
        return Result.ok(issueService.listByOrder(orderId));
    }

    @Operation(summary = "查询领料单明细")
    @GetMapping("/issue/{issueId}/items")
    public Result<List<MaterialIssueItem>> listItems(@PathVariable Long issueId) {
        return Result.ok(issueService.listItems(issueId));
    }

    @Operation(summary = "按业务单号查询库存流水（账本，只增不改）")
    @GetMapping("/transaction/biz")
    public Result<List<InventoryTransaction>> listByBizNo(@RequestParam String bizNo) {
        return Result.ok(issueService.listTransactionsByBizNo(bizNo));
    }
}
