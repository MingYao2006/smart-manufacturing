package com.smart.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smart.inventory.entity.MaterialIssueOutbox;
import com.smart.inventory.mapper.MaterialIssueOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 领料批次关系 outbox 的写入与投递。
 *
 * <h3>两段式，和报工进度用的是两套独立链路</h3>
 * <ol>
 *   <li><b>同事务写</b>：{@link #enqueue} 由领料方法在 {@code @Transactional} 内调用，
 *       扣库存、写流水、写 outbox 要么全成功要么全失败</li>
 *   <li><b>定时投递</b>：{@link #deliverPending} 扫待发消息，HTTP 调质量服务建边</li>
 * </ol>
 *
 * <p>为什么不用 RabbitMQ：这条链路只需要"最终把边建起来"，且质量服务暴露的是 HTTP 接口。
 * 用本地消息表 + 定时重投，比再引入一个 broker 更简单，也不依赖 MQ 是否启动。
 *
 * <p>⚠️ 多实例部署时定时任务会重复扫描，但消费端有 {@code uk_relation_consume}
 * 唯一索引兜底，重复投递只会收到"已存在"，不会建出第二条边。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialIssueOutboxService implements IssueRelationPublisher {

    private final MaterialIssueOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    @Value("${smart.service.quality.base-url:http://127.0.0.1:9005}")
    private String qualityBaseUrl;

    @Value("${smart.inventory.outbox.enabled:true}")
    private boolean enabled;

    @Value("${smart.inventory.outbox.max-retry:5}")
    private int maxRetry;

    /**
     * 在领料本地事务内写入一条待发消息。
     *
     * <p>⚠️ 调用方必须已经在事务里（{@link IssueService#issue}），本方法不自己开事务。
     *
     * <p>写入失败<b>必须上抛</b>，让整个领料事务回滚 —— 这正是 outbox 的意义：
     * "扣库存"和"记消息"要么全成功要么全失败，事务一提交消息必然在库里。
     * 若在这里吞异常，就退化回"料发了本子没写"，原子性保证荡然无存。
     * {@link #repairMissingRelations} 对账兜底防的是手工改库/历史脏数据，
     * 不是给吞异常擦屁股的。
     */
    @Override
    public void enqueue(Long orderId, String orderNo, String issueNo,
                        Long materialId, String materialCode,
                        String batchNo, BigDecimal quantity) {
        if (batchNo == null || batchNo.isBlank()) {
            // 非批次管理物料：没有批次号就无从追溯，跳过（不构成"链断"）
            return;
        }
        if (orderId == null) {
            log.warn("[领料 outbox] 缺少 orderId，跳过 issueNo={} batchNo={}", issueNo, batchNo);
            return;
        }
        String bizKey = orderId + ":" + batchNo;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceBatchNo", batchNo);
        payload.put("productionOrderId", orderId);
        payload.put("orderNo", orderNo);
        payload.put("quantity", quantity);
        payload.put("relationType", "CONSUME");
        // targetBatchNo 故意不传：领料那刻产品批次还不存在，不是漏写

        MaterialIssueOutbox o = new MaterialIssueOutbox();
        o.setBizKey(bizKey);
        o.setOrderId(orderId);
        o.setOrderNo(orderNo);
        o.setIssueNo(issueNo);
        o.setMaterialId(materialId);
        o.setMaterialCode(materialCode);
        o.setBatchNo(batchNo);
        o.setQuantity(quantity);
        try {
            o.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            // payload 全是 String/Long/BigDecimal，理论上不会失败；真失败也必须回滚领料
            throw new IllegalStateException("领料 outbox 消息序列化失败 bizKey=" + bizKey, e);
        }
        int n = outboxMapper.insertIgnore(o);
        if (n > 0) {
            log.debug("[领料 outbox 入队] bizKey={} issueNo={}", bizKey, issueNo);
        } else {
            log.debug("[领料 outbox 入队] 已存在，跳过 bizKey={}", bizKey);
        }
    }

    /**
     * 定时投递：把待发消息 POST 给质量服务建追溯边。
     *
     * <p>幂等键是 {@code orderId + batchNo}，重复投递在消费端被唯一索引挡住，
     * 服务端返回 code=0（幂等成功）时同样标记已投递。
     */
    @Scheduled(fixedDelayString = "${smart.inventory.outbox.delay-ms:3000}",
            initialDelayString = "${smart.inventory.outbox.initial-delay-ms:10000}")
    public void deliverPending() {
        if (!enabled) {
            return;
        }
        List<MaterialIssueOutbox> pending;
        try {
            pending = outboxMapper.selectPending(50);
        } catch (Exception e) {
            log.warn("[领料 outbox 扫描失败] {}", e.getMessage());
            return;
        }
        for (MaterialIssueOutbox msg : pending) {
            sendOne(msg);
        }
    }

    private void sendOne(MaterialIssueOutbox msg) {
        try {
            String raw = RestClient.builder().baseUrl(qualityBaseUrl).build()
                    .post().uri("/batch/relation")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(msg.getPayload())
                    .retrieve().body(String.class);
            // 统一返回体 code=0 表示成功（含"已存在"的幂等返回）
            int code = raw == null ? -1 : objectMapper.readTree(raw).path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("质量服务未返回成功: " + truncate(raw));
            }
            outboxMapper.markSent(msg.getId());
            log.info("[领料 outbox 投递成功] id={} bizKey={} batchNo={}",
                    msg.getId(), msg.getBizKey(), msg.getBatchNo());
        } catch (Exception e) {
            log.warn("[领料 outbox 投递失败] id={} bizKey={} err={}",
                    msg.getId(), msg.getBizKey(), e.getMessage());
            try {
                outboxMapper.markRetry(msg.getId(), truncate(e.getMessage()), maxRetry);
            } catch (Exception ex) {
                log.error("[领料 outbox 标记重试失败] id={}", msg.getId(), ex);
            }
        }
    }

    /**
     * 对账兜底：扫"流水里有批次号、outbox 里却没有"的领料，补录并告警。
     *
     * <p>文档里写得明白：追溯链一旦断裂就是永久性的数据缺陷，事后无法补
     * （不知道当时领了哪批料）。所以宁可多一道检查 —— 只要流水还在，
     * 就能从流水把消息补回来；流水若也没了，就真的补不回来了。
     *
     * <p>这些补录的记录会进入正常投递队列，由 {@link #deliverPending} 建边。
     */
    @Scheduled(fixedDelayString = "${smart.inventory.outbox.reconcile-delay-ms:60000}",
            initialDelayString = "${smart.inventory.outbox.reconcile-initial-delay-ms:30000}")
    public void repairMissingRelations() {
        // 终态失败（重试耗尽）的消息：outbox 行存在，下面的"漏写"扫描查不到它，
        // 但边始终没建起来 —— 必须单独告警，人工介入（重置 status=0 即可重新投递）
        try {
            long failed = outboxMapper.countFailed();
            if (failed > 0) {
                log.error("[领料 outbox] 有 {} 条消息重试耗尽仍投递失败（status=2），追溯边未建立，"
                        + "请排查质量服务后手工重置 status=0 重投", failed);
            }
        } catch (Exception e) {
            log.warn("[领料 outbox] 终态失败计数查询失败 {}", e.getMessage());
        }

        List<Map<String, Object>> missing;
        try {
            missing = outboxMapper.selectMissingRelations(100);
        } catch (Exception e) {
            log.warn("[领料关系对账] 扫描失败 {}", e.getMessage());
            return;
        }
        if (missing.isEmpty()) {
            return;
        }
        int repaired = 0;
        for (Map<String, Object> row : missing) {
            Long orderId = toLong(row.get("order_id"));
            String batchNo = str(row.get("batch_no"));
            String issueNo = str(row.get("issue_no"));
            BigDecimal quantity = toDecimal(row.get("quantity"));
            String orderNo = str(row.get("order_no"));
            String materialCode = str(row.get("material_code"));
            Long materialId = toLong(row.get("material_id"));
            if (orderId == null || batchNo == null) {
                continue;
            }
            try {
                // 复用 enqueue（同样的 INSERT IGNORE 幂等），补录后走正常投递。
                // 对账不在领料事务里，单行失败只记日志，不影响其余行补录
                enqueue(orderId, orderNo, issueNo, materialId, materialCode, batchNo, quantity);
                repaired++;
                log.warn("[领料关系对账] 补录漏写的追溯消息 orderId={} batchNo={} issueNo={} txId={}",
                        orderId, batchNo, issueNo, row.get("tx_id"));
            } catch (Exception e) {
                log.error("[领料关系对账] 补录失败 orderId={} batchNo={} issueNo={}",
                        orderId, batchNo, issueNo, e);
            }
        }
        log.error("[领料关系对账] 发现 {} 条漏写，已补录 {} 条（追溯链断裂风险，请排查写入路径）",
                missing.size(), repaired);
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof Number n ? n.longValue() : Long.valueOf(o.toString());
    }

    private static BigDecimal toDecimal(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null response";
        }
        return s.length() > 480 ? s.substring(0, 480) : s;
    }
}
