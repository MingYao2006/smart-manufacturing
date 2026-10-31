package com.smart.inventory.service;

import com.smart.common.exception.BusinessException;
import com.smart.common.result.ErrorCode;
import com.smart.inventory.dto.InventoryDtos.RequirementItem;
import com.smart.inventory.dto.InventoryDtos.ReserveRequest;
import com.smart.inventory.entity.MaterialInventory;
import com.smart.inventory.entity.MaterialReservation;
import com.smart.inventory.mapper.InventoryTransactionMapper;
import com.smart.inventory.mapper.MaterialInventoryMapper;
import com.smart.inventory.mapper.MaterialReservationMapper;
import com.smart.inventory.support.Stub;
import com.smart.inventory.support.Stub.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存服务单元测试（第 4 周 / 第 7 周任务 7.1）。
 *
 * <p>用 {@link Stub} 的 JDK 动态代理桩替换 Mapper，只测纯业务逻辑。
 * （不用 Mockito 的原因见 {@link Stub} 类注释。）
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li>预留成功：条件更新影响 1 行，写预留记录与流水，且 reserved 增加、quantity 不变</li>
 *   <li>预留不足：抛 STOCK_NOT_ENOUGH，且<b>不写</b>预留记录（事务回滚语义）</li>
 *   <li>幂等：同一工单重复预留直接返回，不重复扣减</li>
 *   <li>可用量三量模型计算</li>
 *   <li>预留剩余量计算</li>
 * </ul>
 */
@DisplayName("库存服务：预留与领料")
class InventoryServiceTest {

    private static final long MATERIAL_ID = 1L;
    private static final long WAREHOUSE_ID = 1L;
    private static final long ORDER_ID = 100L;

    private MaterialInventory inventory;

    private Recorder<MaterialInventoryMapper> inventoryMapper;
    private Recorder<MaterialReservationMapper> reservationMapper;
    private Recorder<InventoryTransactionMapper> transactionMapper;
    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventory = new MaterialInventory();
        inventory.setId(1L);
        inventory.setMaterialId(MATERIAL_ID);
        inventory.setMaterialCode("MAT-MCU-01");
        inventory.setMaterialName("计量芯片 MCU-A");
        inventory.setUnit("颗");
        inventory.setWarehouseId(WAREHOUSE_ID);
        inventory.setQuantity(new BigDecimal("100"));
        inventory.setReservedQuantity(BigDecimal.ZERO);
        inventory.setSafetyStock(new BigDecimal("50"));
    }

    /** 构造被测服务：默认「已预留 0 条、库存记录存在、条件更新成功」 */
    private void buildService(int existingReservations, int reserveStockResult) {
        reservationMapper = Recorder.of(MaterialReservationMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(existingReservations)),
                Stub.rule("insert", 1, Stub.returnsInt(1)));
        inventoryMapper = Recorder.of(MaterialInventoryMapper.class,
                Stub.rule("selectOne", 2, inv -> inventory),
                Stub.rule("reserveStock", 3, Stub.returnsInt(reserveStockResult)));
        transactionMapper = Recorder.of(InventoryTransactionMapper.class,
                Stub.rule("insert", 1, Stub.returnsInt(1)));

        inventoryService = new InventoryService(
                inventoryMapper.proxy(), reservationMapper.proxy(), transactionMapper.proxy());
    }

    private ReserveRequest buildRequest(String qty) {
        RequirementItem item = new RequirementItem();
        item.setMaterialId(MATERIAL_ID);
        item.setMaterialCode("MAT-MCU-01");
        item.setMaterialName("计量芯片 MCU-A");
        item.setUnit("颗");
        item.setRequiredQuantity(new BigDecimal(qty));

        ReserveRequest req = new ReserveRequest();
        req.setOrderId(ORDER_ID);
        req.setOrderNo("WO-TEST");
        req.setWarehouseId(WAREHOUSE_ID);
        req.setItems(List.of(item));
        return req;
    }

    /* ==================== 预留成功 ==================== */

    @Test
    @DisplayName("预留成功：条件更新影响 1 行 → 写预留记录与流水")
    void testReserveSuccess() {
        buildService(0, 1);

        var result = inventoryService.reserve(buildRequest("30"));

        assertEquals(1, result.getReservedCount());
        assertEquals(ORDER_ID, result.getOrderId());

        // 条件更新被调用 1 次，且传入数量正确
        assertEquals(1, inventoryMapper.callCount("reserveStock"));
        Object[] args = inventoryMapper.firstArgs("reserveStock");
        assertEquals(MATERIAL_ID, args[0]);
        assertEquals(WAREHOUSE_ID, args[1]);
        assertEquals(0, ((BigDecimal) args[2]).compareTo(new BigDecimal("30")));

        // 必须写预留记录（幂等与两段式的基础）
        assertEquals(1, reservationMapper.callCount("insert"));
        Object saved = reservationMapper.firstArgs("insert")[0];
        assertTrue(saved instanceof MaterialReservation);
        MaterialReservation r = (MaterialReservation) saved;
        assertEquals(MaterialReservation.STATUS_RESERVED, r.getStatus());
        assertEquals(0, r.getIssuedQuantity().compareTo(BigDecimal.ZERO));
        assertEquals(0, r.getReservedQuantity().compareTo(new BigDecimal("30")));
        assertNotNull(r.getExpireTime(), "必须设置过期时间，防止预留泄漏");
        assertNotNull(r.getReservationNo(), "必须有预留单号");

        // 必须写流水（同事务，用于对账与追溯）
        assertEquals(1, transactionMapper.callCount("insert"));
    }

    /* ==================== 预留不足 ==================== */

    @Test
    @DisplayName("预留不足：条件更新影响 0 行 → 抛 STOCK_NOT_ENOUGH，且不写任何记录")
    void testReserveInsufficient() {
        buildService(0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reserve(buildRequest("150")));

        assertEquals(ErrorCode.STOCK_NOT_ENOUGH.getCode(), ex.getCode(),
                "应返回库存不足错误码 40903");
        assertTrue(ex.getMessage().contains("可用库存不足"),
                "错误信息应说明库存不足，实际: " + ex.getMessage());

        // 关键断言：失败时绝不能写预留记录和流水（否则就是脏数据）
        assertEquals(0, reservationMapper.callCount("insert"),
                "库存不足时不应写预留记录");
        assertEquals(0, transactionMapper.callCount("insert"),
                "库存不足时不应写流水");
    }

    /* ==================== 幂等 ==================== */

    @Test
    @DisplayName("幂等：同一工单已预留过 → 直接返回，不重复扣减")
    void testReserveIdempotent() {
        buildService(3, 1);

        var result = inventoryService.reserve(buildRequest("30"));

        assertEquals(3, result.getReservedCount());
        assertTrue(result.getMessage().contains("幂等"),
                "应提示幂等返回，实际: " + result.getMessage());

        // 绝不能再次扣减预留量
        assertEquals(0, inventoryMapper.callCount("reserveStock"),
                "幂等返回时不应调用条件更新");
        assertEquals(0, reservationMapper.callCount("insert"),
                "幂等返回时不应写预留记录");
    }

    /* ==================== 库存记录不存在 ==================== */

    @Test
    @DisplayName("物料无库存记录时抛 NOT_FOUND，而不是 NPE")
    void testReserveNoInventoryRecord() {
        reservationMapper = Recorder.of(MaterialReservationMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(0)),
                Stub.rule("insert", 1, Stub.returnsInt(1)));
        inventoryMapper = Recorder.of(MaterialInventoryMapper.class,
                Stub.rule("selectOne", 2, inv -> null));
        transactionMapper = Recorder.of(InventoryTransactionMapper.class);
        inventoryService = new InventoryService(
                inventoryMapper.proxy(), reservationMapper.proxy(), transactionMapper.proxy());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> inventoryService.reserve(buildRequest("10")));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    /* ==================== 多物料：排序防死锁 ==================== */

    @Test
    @DisplayName("多物料预留：按 materialId 升序调用（防死锁），且全部成功")
    void testReserveMultipleSortedByMaterialId() {
        MaterialInventory inv1 = new MaterialInventory();
        inv1.setId(1L);
        inv1.setMaterialId(1L);
        inv1.setMaterialCode("MAT-1");
        inv1.setQuantity(new BigDecimal("1000"));
        inv1.setReservedQuantity(BigDecimal.ZERO);

        MaterialInventory inv4 = new MaterialInventory();
        inv4.setId(4L);
        inv4.setMaterialId(4L);
        inv4.setMaterialCode("MAT-4");
        inv4.setQuantity(new BigDecimal("1000"));
        inv4.setReservedQuantity(BigDecimal.ZERO);

        reservationMapper = Recorder.of(MaterialReservationMapper.class,
                Stub.rule("countByOrderId", 1, Stub.returnsInt(0)),
                Stub.rule("insert", 1, Stub.returnsInt(1)));
        inventoryMapper = Recorder.of(MaterialInventoryMapper.class,
                Stub.rule("selectOne", 2, inv -> {
                    Long mid = (Long) inv.arg0();
                    return mid == 4L ? inv4 : inv1;
                }),
                Stub.rule("reserveStock", 3, Stub.returnsInt(1)));
        transactionMapper = Recorder.of(InventoryTransactionMapper.class,
                Stub.rule("insert", 1, Stub.returnsInt(1)));
        inventoryService = new InventoryService(
                inventoryMapper.proxy(), reservationMapper.proxy(), transactionMapper.proxy());

        // 故意【倒序】传入：物料 4 在前，物料 1 在后
        RequirementItem big = new RequirementItem();
        big.setMaterialId(4L);
        big.setMaterialCode("MAT-4");
        big.setRequiredQuantity(new BigDecimal("10"));
        RequirementItem small = new RequirementItem();
        small.setMaterialId(1L);
        small.setMaterialCode("MAT-1");
        small.setRequiredQuantity(new BigDecimal("5"));

        ReserveRequest req = new ReserveRequest();
        req.setOrderId(ORDER_ID);
        req.setItems(List.of(big, small));

        var result = inventoryService.reserve(req);
        assertEquals(2, result.getReservedCount());

        // 关键断言：实际调用顺序必须是 materialId 升序（1 再 4），与入参顺序无关
        List<Object[]> calls = inventoryMapper.allArgs("reserveStock");
        assertEquals(2, calls.size());
        assertEquals(1L, calls.get(0)[0], "第一次应预留物料 1（升序，防死锁）");
        assertEquals(4L, calls.get(1)[0], "第二次应预留物料 4");
    }

    /* ==================== 三量模型计算 ==================== */

    @Test
    @DisplayName("可用量 = 现有量 - 预留量（计算值，不落库）")
    void testAvailableQuantity() {
        inventory.setQuantity(new BigDecimal("100"));
        inventory.setReservedQuantity(new BigDecimal("30"));
        assertEquals(0, inventory.availableQuantity().compareTo(new BigDecimal("70")));

        // 边界：全部被预留
        inventory.setReservedQuantity(new BigDecimal("100"));
        assertEquals(0, inventory.availableQuantity().compareTo(BigDecimal.ZERO));

        // null 安全
        inventory.setQuantity(null);
        inventory.setReservedQuantity(null);
        assertEquals(0, inventory.availableQuantity().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("预留剩余量 = 预留量 - 已领量")
    void testRemainingQuantity() {
        MaterialReservation r = new MaterialReservation();
        r.setReservedQuantity(new BigDecimal("100"));
        r.setIssuedQuantity(new BigDecimal("30"));
        assertEquals(0, r.remainingQuantity().compareTo(new BigDecimal("70")));

        r.setIssuedQuantity(new BigDecimal("100"));
        assertEquals(0, r.remainingQuantity().compareTo(BigDecimal.ZERO));

        // 全部未领
        r.setIssuedQuantity(null);
        assertEquals(0, r.remainingQuantity().compareTo(new BigDecimal("100")));
    }

    /* ==================== 低于安全库存 ==================== */

    @Test
    @DisplayName("低于安全库存判定：可用量 < 安全库存")
    void testBelowSafetyStock() {
        inventory.setQuantity(new BigDecimal("100"));
        inventory.setReservedQuantity(new BigDecimal("30"));
        inventory.setSafetyStock(new BigDecimal("50"));
        // 可用 70 >= 安全 50 → 不低
        assertFalse(availableBelowSafety());
    }

    private boolean availableBelowSafety() {
        return inventory.availableQuantity().compareTo(inventory.getSafetyStock()) < 0;
    }
}
