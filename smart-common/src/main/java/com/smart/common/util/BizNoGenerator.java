package com.smart.common.util;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务编码生成器。
 *
 * <p>格式：{前缀}{yyyyMMdd}{4 位日内序列}，如 {@code WO20250115001}。
 *
 * <p>为什么不用 UUID / 随机数：业务单号要人能读、能排序、能按日期查；
 * 随机数还会导致索引碎片。唯一性由数据库唯一索引兜底（见 sql 脚本）。
 *
 * <p>为什么不用 Redis INCR：引入额外组件只是为了单号，收益极低。
 * 这里采用"内存计数器 + 数据库唯一索引兜底 + 冲突重试"的方案，
 * 在多实例下序列会重复，但唯一索引会拦住，上层重试即可。
 */
@Slf4j
public final class BizNoGenerator {

    private BizNoGenerator() {
    }

    /** 工单号前缀 */
    public static final String ORDER_PREFIX = "WO";
    /** 生产计划前缀 */
    public static final String PLAN_PREFIX = "PP";
    /** 报工单号前缀 */
    public static final String REPORT_PREFIX = "RP";
    /** 领料单号前缀 */
    public static final String ISSUE_PREFIX = "IS";
    /** 预留单号前缀 */
    public static final String RESERVATION_PREFIX = "RS";
    /** 库存流水号前缀 */
    public static final String TRANSACTION_PREFIX = "TX";
    /** 物料批次前缀 */
    public static final String MATERIAL_BATCH_PREFIX = "MB";
    /** 产品批次前缀 */
    public static final String PRODUCT_BATCH_PREFIX = "PB";
    /** 质检单前缀 */
    public static final String INSPECTION_PREFIX = "QC";
    /** 状态流转日志无单号 */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static volatile String cachedDate = "";
    private static int seq = 0;

    /**
     * 生成业务单号。
     *
     * @param prefix 单号前缀，如 WO
     * @return 形如 WO20250115001
     */
    public static synchronized String generate(String prefix) {
        String today = LocalDateTime.now().format(DATE_FMT);
        if (!today.equals(cachedDate)) {
            cachedDate = today;
            // 跨天时随机起始值，减少多实例/重启后的冲突概率
            seq = ThreadLocalRandom.current().nextInt(0, 100);
        }
        seq = (seq + 1) % 10000;
        return prefix + today + String.format("%04d", seq);
    }

    /**
     * 生成带纳秒后缀的单号，用于要求"绝对不重复"且不希望重试的场景（如流水号）。
     */
    public static String generateWithNano(String prefix) {
        String base = generate(prefix);
        long nano = System.nanoTime() % 100000;
        return base + String.format("%04d", nano);
    }

    /* ---------------- 数量工具 ---------------- */

    /**
     * 向上取整到整数（物料不能领半个）。
     */
    public static BigDecimal ceil(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(0, RoundingMode.CEILING);
    }

    public static BigDecimal scale4(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    public static boolean gtZero(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    public static boolean notNegative(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) >= 0;
    }
}
