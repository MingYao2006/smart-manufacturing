import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Smart-Manufacturing 完整业务链路演示 / 联调验证程序。
 *
 * <h2>演示的闭环（对应计划书第 8 周任务 8.2 的演示数据要求）</h2>
 * <pre>
 *   登录
 *    → 查产品/BOM/工艺路线
 *    → 创建生产计划
 *    → 创建工单（自动快照 BOM 与工艺版本）
 *    → 审核 → 下达（展开工序任务）
 *    → 物料需求计算（BOM × 数量 × 损耗率）
 *    → 齐套分析（发现缺料）
 *    → 物料预留（条件更新，防超发）
 *    → 领料（预留转实扣，记录批次）
 *    → 工序开始 → 报工 → 质检点 → 质检放行
 *    → 工单进度重算 → 自动完工
 *    → 生成产品批次 + 回填批次关系
 *    → 反向追溯（成品→原料+供应商）
 *    → 正向追溯（原料→受影响成品，精准召回）
 *    → 库存对账
 * </pre>
 *
 * <h2>运行方式</h2>
 * <pre>
 *   # 1. 启动全部服务（9000-9005）
 *   # 2. 执行：
 *   java demo/FullChainDemo.java
 * </pre>
 *
 * <p>所有请求都走 <b>gateway :9000</b>，走的是"登录 → 拿 token → 带 token 访问"的真实链路，
 * 因此本程序同时验证了网关鉴权、路由转发与服务间调用。
 *
 * <p>退出码：0 表示全链路通过，1 表示有步骤失败。
 */
public class FullChainDemo {

    static final String GATEWAY = System.getenv().getOrDefault("MES_GATEWAY", "http://127.0.0.1:9000");
    static final ObjectMapper JSON = new ObjectMapper();

    static HttpClient http;
    static String token;
    static int passCount = 0;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        header("Smart-Manufacturing 智能制造 MES · 完整业务链路演示");
        System.out.println("网关地址: " + GATEWAY);
        System.out.println();

        // ============ 1. 登录 ============
        step("1. 登录（JWT）");
        JsonNode login = post("/system/auth/login",
                "{\"username\":\"admin\",\"password\":\"123456\"}", false);
        require(login, "登录成功");
        token = login.path("data").path("token").asText();
        String roles = login.path("data").path("user").path("roles").toString();
        info("用户: " + login.path("data").path("user").path("username").asText()
                + "  角色: " + roles);
        info("token: " + token.substring(0, Math.min(48, token.length())) + "...");

        // ============ 2. 验证网关鉴权 ============
        step("2. 验证网关鉴权（无 token 应被拒绝）");
        JsonNode noToken = getRaw("/product-service/product/1", false);
        requireCode(noToken, 40100, "无 token 访问被拒绝");

        // ============ 3. 基础资料 ============
        step("3. 查询基础资料（产品 / 生效 BOM / 生效工艺路线）");
        JsonNode product = get("/product-service/product/1", true);
        require(product, "查产品");
        info("产品: " + product.path("data").path("productCode").asText()
                + " " + product.path("data").path("productName").asText());

        JsonNode bom = get("/product-service/bom/product/1/active", true);
        require(bom, "查生效 BOM");
        long bomId = bom.path("data").path("id").asLong();
        int bomItemCount = bom.path("data").path("items").size();
        info("生效 BOM: id=" + bomId + " version=" + bom.path("data").path("bomVersion").asText()
                + " 明细 " + bomItemCount + " 行");

        JsonNode route = get("/product-service/route/product/1/active", true);
        require(route, "查生效工艺路线");
        long routeId = route.path("data").path("id").asLong();
        int stepCount = route.path("data").path("steps").size();
        info("生效工艺路线: id=" + routeId + " version="
                + route.path("data").path("routeVersion").asText() + " 工序 " + stepCount + " 道");

        // ============ 4. 生产计划 ============
        step("4. 创建生产计划");
        JsonNode plan = post("/production-service/plan",
                "{\"productId\":1,\"planQuantity\":100,"
                        + "\"planStartDate\":\"2025-01-10\",\"planEndDate\":\"2025-01-20\","
                        + "\"remark\":\"全链路演示计划\"}", true);
        require(plan, "创建计划");
        long planId = plan.path("data").path("id").asLong();
        info("计划号: " + plan.path("data").path("planNo").asText() + " 数量 100");

        // ============ 5. 工单（快照） ============
        step("5. 创建工单（自动快照 BOM 与工艺版本）");
        JsonNode order = post("/production-service/order",
                "{\"planId\":" + planId + ",\"productId\":1,\"planQuantity\":100,"
                        + "\"planEndTime\":\"2025-01-20T18:00:00\","
                        + "\"remark\":\"全链路演示工单\"}", true);
        require(order, "创建工单");
        long orderId = order.path("data").path("id").asLong();
        String orderNo = order.path("data").path("orderNo").asText();
        long orderBomId = order.path("data").path("bomId").asLong();
        long orderRouteId = order.path("data").path("routeId").asLong();
        info("工单号: " + orderNo + "  状态: " + order.path("data").path("statusDesc").asText());
        info("快照锁定: bomId=" + orderBomId + "(" + order.path("data").path("bomVersion").asText()
                + ") routeId=" + orderRouteId + "(" + order.path("data").path("routeVersion").asText() + ")");
        check(orderBomId == bomId, "工单快照的 bomId 与生效 BOM 一致");
        check(orderRouteId == routeId, "工单快照的 routeId 与生效工艺路线一致");

        // ============ 6. 状态机：非法迁移被拦截 ============
        step("6. 状态机验证：非法迁移必须被拒绝");
        JsonNode illegal = post("/production-service/order/" + orderId + "/transit",
                "{\"targetStatus\":\"RELEASED\",\"reason\":\"跳过审核\"}", true);
        requireCode(illegal, 40901, "草稿直接下达被拒绝（必须先审核）");

        step("7. 合法流转：审核 → 下达（生成工序任务）");
        require(post("/production-service/order/" + orderId + "/approve", "", true), "审核通过");
        require(post("/production-service/order/" + orderId + "/release", "", true), "工单下达");

        JsonNode tasks = get("/production-service/work-task/order/" + orderId, true);
        require(tasks, "查工序任务");
        int taskCount = tasks.path("data").size();
        info("生成工序任务 " + taskCount + " 条");
        check(taskCount == stepCount, "工序任务数与工艺路线工序数一致（" + stepCount + "）");
        long firstTaskId = tasks.path("data").get(0).path("id").asLong();
        long inspectionTaskId = 0;
        for (JsonNode t : tasks.path("data")) {
            info("  工序 " + t.path("stepNo").asInt() + " " + t.path("stepName").asText()
                    + "  状态=" + t.path("statusDesc").asText()
                    + "  质检点=" + t.path("isInspection").asInt());
            if (t.path("isInspection").asInt() == 1 && inspectionTaskId == 0) {
                inspectionTaskId = t.path("id").asLong();
            }
        }

        // ============ 8. 物料需求计算 ============
        step("8. 物料需求计算（BOM × 计划数量 × 损耗率，向上取整）");
        JsonNode reqs = get("/production-service/order/" + orderId + "/requirements", true);
        require(reqs, "计算物料需求");
        int reqCount = reqs.path("data").size();
        info("需求物料 " + reqCount + " 种：");
        List<long[]> reqList = new ArrayList<>();
        for (JsonNode r : reqs.path("data")) {
            long mid = r.path("materialId").asLong();
            BigDecimal qty = new BigDecimal(r.path("requiredQuantity").asText());
            reqList.add(new long[]{mid, qty.longValue()});
            info(String.format("  %-16s 单位用量=%-6s 损耗=%-6s 需求=%s %s",
                    r.path("materialCode").asText(),
                    r.path("unitQuantity").asText(),
                    r.path("lossRate").asText(),
                    r.path("requiredQuantity").asText(),
                    r.path("unit").asText("")));
        }
        check(reqCount == bomItemCount, "需求项数与 BOM 明细行数一致（" + bomItemCount + "）");
        // 手工核对损耗率：芯片 1×100×1.02 = 102
        long chipQty = -1;
        for (long[] p : reqList) {
            if (p[0] == 1L) {
                chipQty = p[1];
            }
        }
        check(chipQty == 102, "损耗率计算正确：芯片 1×100×(1+2%)=102，实际 " + chipQty);

        // ============ 9. 齐套分析 ============
        step("9. 齐套分析（对比需求与可用量）");
        StringBuilder kitBody = new StringBuilder("[");
        for (JsonNode r : reqs.path("data")) {
            if (kitBody.length() > 1) {
                kitBody.append(",");
            }
            kitBody.append(String.format(
                    "{\"materialId\":%d,\"materialCode\":\"%s\",\"materialName\":\"%s\","
                            + "\"unit\":\"%s\",\"requiredQuantity\":%s}",
                    r.path("materialId").asLong(),
                    r.path("materialCode").asText(),
                    r.path("materialName").asText(),
                    r.path("unit").asText(""),
                    r.path("requiredQuantity").asText()));
        }
        kitBody.append("]");
        JsonNode kitting = post("/inventory-service/inventory/kitting/" + orderId,
                kitBody.toString(), true);
        require(kitting, "齐套分析");
        boolean enough = kitting.path("data").path("enough").asBoolean();
        info("齐套结果: " + (enough ? "物料齐全" : "存在缺料"));
        int shortageCount = 0;
        for (JsonNode it : kitting.path("data").path("items")) {
            if (!it.path("enough").asBoolean()) {
                shortageCount++;
                info("  缺料: " + it.path("materialCode").asText()
                        + " 需求=" + it.path("requiredQuantity").asText()
                        + " 可用=" + it.path("availableQuantity").asText()
                        + " 缺口=" + it.path("shortage").asText());
            }
        }
        info("缺料项数: " + shortageCount);

        // ============ 10. 预留（只对充足的物料） ============
        step("10. 物料预留（条件更新，防超发）");
        List<long[]> reservable = new ArrayList<>();
        for (JsonNode it : kitting.path("data").path("items")) {
            if (it.path("enough").asBoolean()) {
                reservable.add(new long[]{it.path("materialId").asLong(),
                        new BigDecimal(it.path("requiredQuantity").asText()).longValue()});
            }
        }
        // 只预留前 3 种充足物料，便于演示（避免超出演示库存）
        int reserveLimit = Math.min(3, reservable.size());
        StringBuilder reserveBody = new StringBuilder(
                "{\"orderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\",\"warehouseId\":1,\"items\":[");
        for (int i = 0; i < reserveLimit; i++) {
            if (i > 0) {
                reserveBody.append(",");
            }
            reserveBody.append(String.format("{\"materialId\":%d,\"requiredQuantity\":%d}",
                    reservable.get(i)[0], reservable.get(i)[1]));
        }
        reserveBody.append("]}");
        JsonNode reserve = post("/inventory-service/reservation/reserve", reserveBody.toString(), true);
        require(reserve, "物料预留");
        info(reserve.path("data").path("message").asText());

        // 预留后可用量应变小、现有量不变
        long firstMaterialId = reservable.get(0)[0];
        JsonNode invBefore = get("/inventory-service/inventory/" + firstMaterialId, true);
        info("预留后物料 " + firstMaterialId + ": 现有="
                + invBefore.path("data").path("quantity").asText()
                + " 预留=" + invBefore.path("data").path("reservedQuantity").asText()
                + " 可用=" + invBefore.path("data").path("availableQuantity").asText());
        check(new BigDecimal(invBefore.path("data").path("reservedQuantity").asText())
                        .compareTo(BigDecimal.ZERO) > 0,
                "预留后 reserved_quantity 增加（物料仍在仓库，quantity 不变）");

        // ============ 11. 物料批次（追溯起点） ============
        step("11. 物料入库生成批次（批次是追溯链路的起点，带供应商）");
        String[] suppliers = {"深圳华芯电子有限公司", "东莞精塑制品厂", "苏州标准件有限公司"};
        for (int i = 0; i < reserveLimit; i++) {
            long mid = reservable.get(i)[0];
            JsonNode mb = post("/quality-service/batch/material",
                    "{\"materialId\":" + mid + ",\"materialCode\":\"MAT-" + mid + "\","
                            + "\"materialName\":\"演示物料" + mid + "\","
                            + "\"quantity\":5000,"
                            + "\"batchNo\":\"MB-DEMO-" + mid + "\","
                            + "\"supplierId\":" + (1001 + i) + ","
                            + "\"supplierName\":\"" + suppliers[i % suppliers.length] + "\"}", true);
            if (mb.path("code").asInt() == 0) {
                passCount++;
                info("物料批次: MB-DEMO-" + mid + "  供应商=" + suppliers[i % suppliers.length]);
            } else {
                // 批次已存在（重复运行时）不算失败
                info("物料批次 MB-DEMO-" + mid + " 已存在，跳过");
            }
        }

        // ============ 12. 领料 ============
        step("12. 领料（预留转实扣，记录批次号）");
        StringBuilder issueBody = new StringBuilder(
                "{\"issueNo\":\"IS-DEMO-" + System.currentTimeMillis() + "\","
                        + "\"orderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\","
                        + "\"warehouseId\":1,\"operatorName\":\"李仓管\",\"items\":[");
        for (int i = 0; i < reserveLimit; i++) {
            if (i > 0) {
                issueBody.append(",");
            }
            issueBody.append(String.format(
                    "{\"materialId\":%d,\"quantity\":%d,\"batchNo\":\"MB-DEMO-%d\"}",
                    reservable.get(i)[0], reservable.get(i)[1], reservable.get(i)[0]));
        }
        issueBody.append("]}");
        JsonNode issue = post("/inventory-service/issue", issueBody.toString(), true);
        require(issue, "领料");
        info("领料单: " + issue.path("data").path("issueNo").asText()
                + " 合计 " + issue.path("data").path("totalQuantity").asText());

        JsonNode invAfter = get("/inventory-service/inventory/" + firstMaterialId, true);
        info("领料后物料 " + firstMaterialId + ": 现有="
                + invAfter.path("data").path("quantity").asText()
                + " 预留=" + invAfter.path("data").path("reservedQuantity").asText());
        check(new BigDecimal(invAfter.path("data").path("quantity").asText())
                        .compareTo(new BigDecimal(invBefore.path("data").path("quantity").asText())) < 0,
                "领料后 quantity 减少（物料离开仓库）");

        // ============ 13. 工序执行 ============
        step("13. 工序流转：开始 → 分批报工 → 完工");
        // 工单进入生产中
        require(post("/production-service/order/" + orderId + "/transit",
                "{\"targetStatus\":\"IN_PROGRESS\"}", true), "工单进入生产中");

        // 首道工序：开始 → 先报 60（未报满，保持进行中）
        require(post("/production-service/work-task/" + firstTaskId + "/start", "", true),
                "首道工序开始");
        JsonNode partial = post("/production-service/report",
                "{\"taskId\":" + firstTaskId
                        + ",\"reportQuantity\":60,\"qualifiedQuantity\":59,\"defectQuantity\":1,"
                        + "\"actualHours\":14,\"operatorName\":\"王工\"}", true);
        require(partial, "首道工序分批报工 60（59 合格 + 1 不良）");
        info("报工后累计: " + partial.path("data").path("reportedQuantity").asText()
                + "/" + partial.path("data").path("planQuantity").asText()
                + "  状态=" + partial.path("data").path("taskStatusDesc").asText());
        check("进行中".equals(partial.path("data").path("taskStatusDesc").asText()),
                "未报满时工序保持【进行中】");

        // 超报校验：再报 50 → 累计 110 > 100，必须拒绝
        JsonNode over = post("/production-service/report",
                "{\"taskId\":" + firstTaskId
                        + ",\"reportQuantity\":50,\"qualifiedQuantity\":50,\"defectQuantity\":0}", true);
        requireCode(over, 40905, "超报被拒绝（60+50=110 > 计划 100）");

        // 数量不匹配校验：合格+不良 ≠ 报工数
        JsonNode mismatch = post("/production-service/report",
                "{\"taskId\":" + firstTaskId
                        + ",\"reportQuantity\":10,\"qualifiedQuantity\":5,\"defectQuantity\":2}", true);
        requireCode(mismatch, 40000, "合格数+不良数≠报工数 被拒绝");

        // 报满：补报剩余 40
        JsonNode full = post("/production-service/report",
                "{\"taskId\":" + firstTaskId
                        + ",\"reportQuantity\":40,\"qualifiedQuantity\":39,\"defectQuantity\":1,"
                        + "\"actualHours\":10}", true);
        require(full, "补报 40 件，累计报满 100");
        info("报满后状态: " + full.path("data").path("taskStatusDesc").asText());

        // ============ 13. 质检门禁 ============
        step("14. 质检门禁：质检点工序必须质检合格才能放行");
        if (inspectionTaskId > 0) {
            // 让质检点工序走到 WAIT_INSPECTION
            require(post("/production-service/work-task/" + inspectionTaskId + "/start", "", true),
                    "质检点工序开始");
            JsonNode insReport = post("/production-service/report",
                    "{\"taskId\":" + inspectionTaskId
                            + ",\"reportQuantity\":100,\"qualifiedQuantity\":97,\"defectQuantity\":3,"
                            + "\"actualHours\":50}", true);
            require(insReport, "质检点工序报满 100");
            info("质检点工序状态: " + insReport.path("data").path("taskStatusDesc").asText());
            check("待质检".equals(insReport.path("data").path("taskStatusDesc").asText()),
                    "质检点报满后进入【待质检】而非【已完成】");

            // 后道工序此时不能开始
            long nextTaskId = 0;
            for (JsonNode t : tasks.path("data")) {
                if (t.path("id").asLong() != inspectionTaskId
                        && t.path("stepNo").asInt() > 20) {
                    nextTaskId = t.path("id").asLong();
                    break;
                }
            }
            if (nextTaskId > 0) {
                JsonNode blocked = post("/production-service/work-task/" + nextTaskId + "/start", "", true);
                requireCode(blocked, 40906, "前道质检未放行时，后道不能开始（工艺纪律）");
            }

            // 录入质检（合格）→ 放行
            step("15. 录入质检单并放行");
            JsonNode inspection = post("/quality-service/inspection",
                    "{\"inspectionType\":5,\"orderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\","
                            + "\"taskId\":" + inspectionTaskId + ",\"stepNo\":20,\"stepName\":\"老化测试\","
                            + "\"inspectionQuantity\":100,\"qualifiedQuantity\":97,\"defectQuantity\":3,"
                            + "\"result\":1,\"inspectorName\":\"赵质检\","
                            + "\"records\":["
                            + "{\"itemName\":\"外观\",\"standardValue\":\"无划痕\",\"actualValue\":\"合格\",\"isQualified\":1},"
                            + "{\"itemName\":\"计量精度\",\"standardValue\":\"±0.5%\",\"actualValue\":\"±0.2%\",\"isQualified\":1}"
                            + "]}", true);
            require(inspection, "创建质检单");
            info("质检单: " + inspection.path("data").path("inspectionNo").asText()
                    + " 结果=" + inspection.path("data").path("resultDesc").asText());

            require(post("/production-service/work-task/" + inspectionTaskId + "/pass-inspection", "", true),
                    "质检放行（工序转已完成）");
        }

        // ============ 15. 工单完工 ============
        step("16. 完成剩余工序 → 工单进度重算 → 自动完工");
        JsonNode tasksNow = get("/production-service/work-task/order/" + orderId, true);
        for (JsonNode t : tasksNow.path("data")) {
            long tid = t.path("id").asLong();
            int st = t.path("status").asInt();
            if (st == 4 || st == 5) {
                continue;   // 已完成/已跳过
            }
            if (st == 1) {   // PENDING
                post("/production-service/work-task/" + tid + "/start", "", true);
            }
            // 若还未报满则补报
            JsonNode cur = get("/production-service/work-task/" + tid, true);
            BigDecimal reported = new BigDecimal(cur.path("data").path("reportedQuantity").asText());
            BigDecimal planQty = new BigDecimal(cur.path("data").path("planQuantity").asText());
            BigDecimal remain = planQty.subtract(reported);
            if (remain.compareTo(BigDecimal.ZERO) > 0) {
                post("/production-service/report",
                        "{\"taskId\":" + tid
                                + ",\"reportQuantity\":" + remain.toPlainString()
                                + ",\"qualifiedQuantity\":" + remain.toPlainString()
                                + ",\"defectQuantity\":0,\"actualHours\":10}", true);
            }
            // 报满后可能是 WAIT_INSPECTION，需要质检放行
            JsonNode after = get("/production-service/work-task/" + tid, true);
            if ("待质检".equals(after.path("data").path("statusDesc").asText())) {
                post("/quality-service/inspection",
                        "{\"inspectionType\":5,\"orderId\":" + orderId + ",\"taskId\":" + tid
                                + ",\"inspectionQuantity\":" + remain.toPlainString()
                                + ",\"qualifiedQuantity\":" + remain.toPlainString()
                                + ",\"defectQuantity\":0,\"result\":1,\"inspectorName\":\"赵质检\"}", true);
                post("/production-service/work-task/" + tid + "/pass-inspection", "", true);
            }
        }

        // 重算进度（MQ 消费者做的事；MQ 未启用时手动触发，逻辑完全一致）
        require(post("/production-service/order/" + orderId + "/recalculate", "", true), "工单进度重算");
        JsonNode orderFinal = get("/production-service/order/" + orderId, true);
        String finalStatus = orderFinal.path("data").path("order").path("statusDesc").asText();
        int progress = orderFinal.path("data").path("order").path("progress").asInt();
        info("工单最终状态: " + finalStatus + "  进度: " + progress + "%");
        info("累计报工: " + orderFinal.path("data").path("order").path("completedQuantity").asText()
                + "  合格: " + orderFinal.path("data").path("order").path("qualifiedQuantity").asText()
                + "  不良: " + orderFinal.path("data").path("order").path("defectQuantity").asText());
        check("已完工".equals(finalStatus), "所有工序完成后工单自动完工");
        check(progress == 100, "工单进度为 100%");

        // 幂等：再重算一次，数字不应翻倍
        require(post("/production-service/order/" + orderId + "/recalculate", "", true), "再次重算（幂等验证）");
        JsonNode orderAgain = get("/production-service/order/" + orderId, true);
        String completedAgain = orderAgain.path("data").path("order").path("completedQuantity").asText();
        check(new BigDecimal(completedAgain).compareTo(
                        new BigDecimal(orderFinal.path("data").path("order").path("completedQuantity").asText())) == 0,
                "进度重算幂等：重复重算数字不翻倍（" + completedAgain + "）");

        // ============ 16. 批次与追溯 ============
        step("17. 生成产品批次并建立追溯关系");
        String productBatchNo = "PB-DEMO-" + orderId;
        JsonNode pb = post("/quality-service/batch/product",
                "{\"productId\":1,\"productCode\":\"PRD-DB-X1\",\"productName\":\"智能电表 X1\","
                        + "\"productionOrderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\","
                        + "\"quantity\":100,\"qualifiedQuantity\":98,\"defectQuantity\":2,"
                        + "\"batchNo\":\"" + productBatchNo + "\"}", true);
        require(pb, "创建产品批次");
        info("产品批次: " + productBatchNo);

        // 记录"物料批次 → 产品批次"关系（真实场景由领料/完工自动写入）
        int relationCount = 0;
        for (int i = 0; i < reserveLimit; i++) {
            JsonNode rel = post("/quality-service/batch/relation",
                    "{\"sourceBatchNo\":\"MB-DEMO-" + reservable.get(i)[0] + "\","
                            + "\"targetBatchNo\":\"" + productBatchNo + "\","
                            + "\"productionOrderId\":" + orderId + ",\"orderNo\":\"" + orderNo + "\","
                            + "\"quantity\":" + reservable.get(i)[1] + ",\"relationType\":\"CONSUME\"}", true);
            if (rel.path("code").asInt() == 0) {
                relationCount++;
            }
        }
        info("记录批次关系 " + relationCount + " 条");

        step("18. 反向追溯（成品 → 原料 + 供应商）");
        JsonNode backward = get("/quality-service/trace/backward?batchNo=" + productBatchNo, true);
        require(backward, "反向追溯");
        info(backward.path("data").path("message").asText());
        printTrace(backward.path("data").path("tree"), 1);
        int backwardNodes = backward.path("data").path("totalNodes").asInt();
        check(backwardNodes >= 2, "反向追溯能查到上游物料批次（" + backwardNodes + " 个批次）");

        step("19. 正向追溯（原料 → 受影响的成品批次，精准召回）");
        JsonNode forward = get("/quality-service/trace/forward?batchNo=MB-DEMO-" + firstMaterialId, true);
        require(forward, "正向追溯");
        info(forward.path("data").path("message").asText());
        printTrace(forward.path("data").path("tree"), 1);
        check(forward.path("data").path("totalNodes").asInt() >= 2,
                "正向追溯能查到下游成品批次");

        // ============ 19. 库存对账 ============
        step("20. 库存对账（库存表 vs 流水账本）");
        JsonNode reconcile = post("/inventory-service/inventory/reconcile", "", true);
        require(reconcile, "执行对账");
        int anomalies = reconcile.path("data").asInt();
        info("对账结果: " + reconcile.path("message").asText());
        check(anomalies == 0, "对账无异常（现有量与流水累加一致）");

        // ============ 汇总 ============
        header("演示结果汇总");
        System.out.println("  通过检查点: " + passCount);
        System.out.println("  失败检查点: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("  失败明细：");
            for (String f : failures) {
                System.out.println("    ✗ " + f);
            }
        }
        System.out.println();
        System.out.println(failures.isEmpty()
                ? "*** 完整业务闭环验证通过 ***"
                : "*** 存在失败项，见上方明细 ***");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /* ==================== HTTP 工具 ==================== */

    static JsonNode get(String path, boolean auth) throws Exception {
        return request("GET", path, null, auth);
    }

    static JsonNode getRaw(String path, boolean auth) throws Exception {
        return request("GET", path, null, auth);
    }

    static JsonNode post(String path, String body, boolean auth) throws Exception {
        return request("POST", path, body, auth);
    }

    static JsonNode request(String method, String path, String body, boolean auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(GATEWAY + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8");
        if (auth && token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
        }
        HttpResponse<String> resp = http.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String text = resp.body();
        if (text == null || text.isBlank()) {
            ObjectNode n = JSON.createObjectNode();
            n.put("code", resp.statusCode() == 401 ? 40100 : resp.statusCode());
            n.put("message", "empty body (HTTP " + resp.statusCode() + ")");
            return n;
        }
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            ObjectNode n = JSON.createObjectNode();
            n.put("code", -1);
            n.put("message", "非 JSON 响应: " + text);
            return n;
        }
    }

    /* ==================== 断言与输出 ==================== */

    static void require(JsonNode node, String label) {
        int code = node.path("code").asInt(-1);
        if (code == 0) {
            passCount++;
        } else {
            failures.add(label + " → code=" + code + " " + node.path("message").asText());
            System.out.println("    ✗ " + label + " code=" + code + " " + node.path("message").asText());
        }
    }

    static void requireCode(JsonNode node, int expectedCode, String label) {
        int code = node.path("code").asInt(-1);
        if (code == expectedCode) {
            passCount++;
            System.out.println("    ✓ " + label + "（返回 " + expectedCode + "）");
        } else {
            failures.add(label + " → 期望 code=" + expectedCode + " 实际=" + code
                    + " " + node.path("message").asText());
            System.out.println("    ✗ " + label + " 期望 " + expectedCode + " 实际 " + code
                    + " " + node.path("message").asText());
        }
    }

    static void check(boolean ok, String label) {
        if (ok) {
            passCount++;
            System.out.println("    ✓ " + label);
        } else {
            failures.add(label);
            System.out.println("    ✗ " + label);
        }
    }

    static void step(String title) {
        System.out.println();
        System.out.println("▶ " + title);
    }

    static void info(String msg) {
        System.out.println("    " + msg);
    }

    static void header(String title) {
        System.out.println("=".repeat(70));
        System.out.println("  " + title);
        System.out.println("=".repeat(70));
    }

    static void printTrace(JsonNode nodes, int level) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode n : nodes) {
            String indent = "    " + "   ".repeat(level - 1);
            String supplier = n.path("supplierName").isMissingNode()
                    || n.path("supplierName").isNull() ? "" : "  供应商=" + n.path("supplierName").asText();
            System.out.println(indent + "└─ [" + n.path("batchNo").asText() + "] "
                    + n.path("code").asText() + " " + n.path("name").asText("")
                    + "  数量=" + n.path("edgeQuantity").asText("")
                    + supplier);
            printTrace(n.path("children"), level + 1);
        }
    }
}
