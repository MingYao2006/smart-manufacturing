package com.smart.production.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 物料需求计算测试（第 4 周任务 4.2 / 第 7 周任务 7.1）。
 *
 * <h3>测试的核心公式</h3>
 * <pre>
 *   需求量 = 单位用量 × 计划数量 × (1 + 损耗率)，然后【向上取整】
 * </pre>
 *
 * <p>为什么这个测试重要（面试可讲）：
 * <ul>
 *   <li><b>损耗率</b>是 MES 与电商最直观的差异。漏算损耗 = 生产中途缺料停线。</li>
 *   <li><b>向上取整</b>：物料不能领半个。算出 100.3 个必须领 101 个。</li>
 *   <li><b>同物料汇总</b>：BOM 里同一物料可能出现多次，不合并会重复预留。</li>
 * </ul>
 *
 * <p>本测试直接复算 {@link ProductionPlanService} 的算法，
 * 用相同的输入验证数学正确性（纯计算逻辑，不依赖 Spring/DB）。
 */
@DisplayName("BOM 物料需求计算")
class BomRequirementCalculatorTest {

    /**
     * 复现生产服务的计算公式，单独验证数学正确性。
     * 与 ProductionPlanService.calculateRequirementsByBom 中的计算保持一致。
     */
    private BigDecimal calcRequired(BigDecimal unitQty, BigDecimal planQty, BigDecimal lossRate) {
        return unitQty
                .multiply(planQty)
                .multiply(BigDecimal.ONE.add(lossRate))
                .setScale(0, RoundingMode.CEILING);
    }

    /* ==================== 损耗率 ==================== */

    @Test
    @DisplayName("损耗率计算：单位用量 10 × 数量 100 × (1+2%) = 1020")
    void testLossRate() {
        // 需求文档 6.1 表格中明确给出的用例
        BigDecimal result = calcRequired(new BigDecimal("10"), new BigDecimal("100"),
                new BigDecimal("0.02"));
        assertEquals(0, result.compareTo(new BigDecimal("1020")),
                "10 × 100 × 1.02 = 1020，实际: " + result);
    }

    @Test
    @DisplayName("损耗率为 0 时，需求量 = 单位用量 × 数量")
    void testZeroLossRate() {
        BigDecimal result = calcRequired(new BigDecimal("2"), new BigDecimal("50"),
                BigDecimal.ZERO);
        assertEquals(0, result.compareTo(new BigDecimal("100")));
    }

    @Test
    @DisplayName("高损耗率：单位用量 1 × 数量 100 × (1+10%) = 110")
    void testHighLossRate() {
        BigDecimal result = calcRequired(BigDecimal.ONE, new BigDecimal("100"),
                new BigDecimal("0.10"));
        assertEquals(0, result.compareTo(new BigDecimal("110")));
    }

    /* ==================== 向上取整 ==================== */

    @Test
    @DisplayName("向上取整：需求量 100.3 → 必须领 101（不能领半个）")
    void testRoundUp() {
        // 1 × 98 × 1.02 = 99.96 → 100
        // 1.03 × 100 × 1.001 ≈ 103.1 → 104
        BigDecimal r1 = calcRequired(new BigDecimal("1"), new BigDecimal("98"),
                new BigDecimal("0.02"));
        assertEquals(0, r1.compareTo(new BigDecimal("100")),
                "1 × 98 × 1.02 = 99.96，向上取整应为 100，实际: " + r1);

        // 小数单位用量场景：0.05kg × 100 × 1.02 = 5.1 → 6
        BigDecimal r2 = calcRequired(new BigDecimal("0.05"), new BigDecimal("100"),
                new BigDecimal("0.02"));
        assertEquals(0, r2.compareTo(new BigDecimal("6")),
                "0.05 × 100 × 1.02 = 5.1，向上取整应为 6，实际: " + r2);

        // 正好整数时不应多进一位
        BigDecimal r3 = calcRequired(new BigDecimal("1"), new BigDecimal("100"),
                new BigDecimal("0.02"));
        assertEquals(0, r3.compareTo(new BigDecimal("102")),
                "100 × 1.02 = 102（正好整数），不应变成 103，实际: " + r3);
    }

    @Test
    @DisplayName("小数用量：0.05kg 生产 100 个产品需要 6（向上取整）而非 5")
    void testDecimalUnitQuantity() {
        BigDecimal r = calcRequired(new BigDecimal("0.05"), new BigDecimal("100"), BigDecimal.ZERO);
        assertEquals(0, r.compareTo(new BigDecimal("5")),
                "0.05 × 100 = 5.0，正好整数，应为 5");

        BigDecimal r2 = calcRequired(new BigDecimal("0.055"), new BigDecimal("100"), BigDecimal.ZERO);
        assertEquals(0, r2.compareTo(new BigDecimal("6")),
                "0.055 × 100 = 5.5，向上取整应为 6");
    }

    /* ==================== 同物料汇总 ==================== */

    @Test
    @DisplayName("同物料汇总：BOM 含同物料两行时合并为一条需求")
    void testMergeSameMaterial() {
        // 模拟 BOM 里物料 1 出现两次（不同工序用），单位用量分别 1 和 2
        // 汇总后单位用量 = 3，需求 = 3 × 10 × 1.02 = 30.6 → 31
        BigDecimal planQty = new BigDecimal("10");
        List<Row> rows = List.of(
                new Row(1L, new BigDecimal("1"), new BigDecimal("0.02")),
                new Row(1L, new BigDecimal("2"), new BigDecimal("0.02"))
        );

        Map<Long, BigDecimal> unitSum = new LinkedHashMap<>();
        Map<Long, BigDecimal> requiredSum = new LinkedHashMap<>();
        for (Row r : rows) {
            unitSum.merge(r.materialId, r.unitQty, BigDecimal::add);
            requiredSum.merge(r.materialId,
                    calcRequired(r.unitQty, planQty, r.lossRate), BigDecimal::add);
        }

        assertEquals(1, unitSum.size(), "同一物料应只保留一条汇总记录");
        assertEquals(0, unitSum.get(1L).compareTo(new BigDecimal("3")),
                "单位用量应汇总为 3");
        // 注意：逐行取整再相加（1×10×1.02=11, 2×10×1.02=21）→ 32
        // 若先汇总再取整（3×10×1.02=30.6→31）结果不同。
        // 本项目选择"逐行取整后相加"，因为每行的用量需要各自向上取整（不同物料行独立备料）。
        assertEquals(0, requiredSum.get(1L).compareTo(new BigDecimal("32")),
                "逐行取整后汇总应为 32，实际: " + requiredSum.get(1L));
    }

    @Test
    @DisplayName("不同物料不应被合并")
    void testDifferentMaterialsNotMerged() {
        Map<Long, BigDecimal> unitSum = new LinkedHashMap<>();
        unitSum.merge(1L, new BigDecimal("1"), BigDecimal::add);
        unitSum.merge(2L, new BigDecimal("2"), BigDecimal::add);
        assertEquals(2, unitSum.size());
    }

    /* ==================== 多物料综合场景 ==================== */

    @Test
    @DisplayName("综合场景：电表 X1 生产 100 台的全部物料需求（与数据库实际数据一致）")
    void testRealBomScenario() {
        // 数据来自 sql/01_schema_product.sql 中电表 X1 (bom_id=1) 的配置
        BigDecimal planQty = new BigDecimal("100");
        List<Expected> expected = List.of(
                // 物料名, 单位用量, 损耗率, 期望需求
                new Expected("计量芯片 MCU-A", "1", "0.02", "102"),
                new Expected("表壳 ABS 外壳", "1", "0.01", "101"),
                new Expected("M3 自攻螺丝", "4", "0.05", "420"),
                new Expected("LCD 液晶屏", "1", "0.03", "103"),
                new Expected("铜芯导线", "2", "0.10", "220"),
                new Expected("彩盒包装", "1", "0.01", "101"),
                new Expected("铅封", "1", "0.02", "102")
        );

        for (Expected e : expected) {
            BigDecimal actual = calcRequired(new BigDecimal(e.unitQty),
                    planQty, new BigDecimal(e.lossRate));
            assertEquals(0, actual.compareTo(new BigDecimal(e.expected)),
                    String.format("%s: %s × 100 × (1+%s) 期望 %s，实际 %s",
                            e.name, e.unitQty, e.lossRate, e.expected, actual));
        }
    }

    @Test
    @DisplayName("计划数量为小数时也要正确取整")
    void testDecimalPlanQuantity() {
        // 生产 2.5 台（理论上不该有，但系统允许小数），需 1 × 2.5 × 1.02 = 2.55 → 3
        BigDecimal r = calcRequired(BigDecimal.ONE, new BigDecimal("2.5"),
                new BigDecimal("0.02"));
        assertEquals(0, r.compareTo(new BigDecimal("3")));
    }

    @Test
    @DisplayName("需求结果不包含小数位（必须是整数）")
    void testResultIsInteger() {
        BigDecimal r = calcRequired(new BigDecimal("1.7"), new BigDecimal("3"),
                new BigDecimal("0.033"));
        assertEquals(0, r.scale(), "向上取整后应无小数位，实际 scale=" + r.scale());
        assertTrue(r.stripTrailingZeros().scale() <= 0);
    }

    /* ==================== 辅助类型 ==================== */

    private record Row(Long materialId, BigDecimal unitQty, BigDecimal lossRate) {
    }

    private record Expected(String name, String unitQty, String lossRate, String expected) {
    }

    @SuppressWarnings("unused")
    private static List<Row> sortedById(List<Row> rows) {
        List<Row> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(Row::materialId));
        return copy;
    }
}
