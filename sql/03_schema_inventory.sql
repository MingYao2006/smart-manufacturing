-- =====================================================================
-- 03 · 库存库 smart_inventory
-- 表：material_inventory / inventory_transaction / material_reservation
--     / material_issue / material_issue_item / inventory_reconcile_log
-- =====================================================================

USE smart_inventory;

-- ---------------------------------------------------------------------
-- 物料库存表：本项目的并发控制核心表。
--
-- 🔑 三量模型：
--      quantity            现有量（仓库里实际有多少）
--      reserved_quantity   预留量（被工单锁定但还没领走）
--      可用量 = quantity - reserved_quantity  ← 计算值，不存字段，避免三个数不一致
--
-- 🔑 uk_material_warehouse (material_id, warehouse_id)：
--    所有条件更新依赖"单行记录"。若同物料同仓库有多条，UPDATE 会命中多行，
--    "影响行数=1"的判断就失效了。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS material_inventory;
CREATE TABLE material_inventory (
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    material_id        BIGINT        NOT NULL COMMENT '物料',
    material_code      VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照（反范式，避免跨库 JOIN）',
    material_name      VARCHAR(128)           DEFAULT NULL COMMENT '物料名称快照',
    unit               VARCHAR(16)            DEFAULT NULL COMMENT '单位快照',
    warehouse_id       BIGINT        NOT NULL DEFAULT 1 COMMENT '仓库（本期单仓库，默认 1）',
    quantity           DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '现有量：CHECK (quantity >= 0)',
    reserved_quantity  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '预留量：CHECK (reserved >= 0 AND reserved <= quantity)',
    safety_stock       DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '安全库存快照，用于低库存预警',
    version            INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（本方案用条件更新，此列留作扩展）',
    create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_warehouse (material_id, warehouse_id) COMMENT '🔑同物料同仓库只有一条记录，并发更新的基础',
    KEY idx_inventory_material (material_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物料库存表（三量模型）';

-- ---------------------------------------------------------------------
-- 库存流水表：库存变动的账本。【只增不改不删】
--
-- 🔑 before_quantity / after_quantity：
--      ① 对账：末条流水 after 应等于库存表 quantity
--      ② 排查并发 bug：两条流水 before 相同说明有并发覆盖
--      ③ 审计：任何时刻库存都可由流水重建
-- 🔑 batch_no：批次追溯链路的起点。没有它追溯就断了。
-- 发现错误用"反向冲正"（再记一条相反流水），绝不修改历史。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS inventory_transaction;
CREATE TABLE inventory_transaction (
    id                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    transaction_no    VARCHAR(64)   NOT NULL COMMENT '流水号（唯一）',
    material_id       BIGINT        NOT NULL COMMENT '物料',
    material_code     VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    warehouse_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '仓库',
    batch_no          VARCHAR(64)            DEFAULT NULL COMMENT '批次号（批次管理物料必填），追溯起点',
    transaction_type  TINYINT       NOT NULL COMMENT '1-入库 2-出库 3-预留 4-释放预留 5-退料 6-盘点调整 7-预留占用冲正',
    quantity          DECIMAL(18,4) NOT NULL COMMENT '变动量（正负号表示方向）',
    before_quantity   DECIMAL(18,4) NOT NULL COMMENT '变动前现有量',
    after_quantity    DECIMAL(18,4) NOT NULL COMMENT '变动后现有量',
    before_reserved   DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '变动前预留量',
    after_reserved    DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '变动后预留量',
    biz_type          VARCHAR(32)            DEFAULT NULL COMMENT '业务类型（领料/入库/预留/退料）',
    biz_no            VARCHAR(64)            DEFAULT NULL COMMENT '业务单号（关联领料单/工单）',
    operator_id       BIGINT                 DEFAULT NULL COMMENT '操作人',
    operator_name     VARCHAR(64)            DEFAULT NULL COMMENT '操作人姓名',
    remark            VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_time       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发生时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_no (transaction_no),
    KEY idx_tx_material_batch (material_id, batch_no),
    KEY idx_tx_biz_no (biz_no),
    KEY idx_tx_create_time (create_time),
    KEY idx_tx_material_warehouse (material_id, warehouse_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存流水表（账本，只增不改）';

-- ---------------------------------------------------------------------
-- 物料预留表：记录"工单锁定了哪些物料、锁了多少、领了多少"。
-- 预留与实扣两段式的核心。
--
-- 🔑 uk_order_material：同一工单同一物料只预留一次，天然幂等。
-- 🔑 expire_time：预留泄漏的兜底 —— 工单取消/长期不生产时定时任务释放，
--    否则库存被永久锁死。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS material_reservation;
CREATE TABLE material_reservation (
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    reservation_no     VARCHAR(64)   NOT NULL COMMENT '预留单号',
    order_id           BIGINT        NOT NULL COMMENT '工单',
    order_no           VARCHAR(64)            DEFAULT NULL COMMENT '冗余工单号',
    material_id        BIGINT        NOT NULL COMMENT '物料',
    material_code      VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    material_name      VARCHAR(128)           DEFAULT NULL COMMENT '物料名称快照',
    warehouse_id       BIGINT        NOT NULL DEFAULT 1 COMMENT '仓库',
    reserved_quantity  DECIMAL(18,4) NOT NULL COMMENT '预留数量',
    issued_quantity    DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已领用数量（领料时校验 领用量<=预留-已领）',
    status             TINYINT       NOT NULL DEFAULT 1 COMMENT '1-已预留 2-部分领用 3-全部领用 4-已释放',
    expire_time        DATETIME               DEFAULT NULL COMMENT '预留过期时间：防预留泄漏',
    released_time      DATETIME               DEFAULT NULL COMMENT '释放时间',
    create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_no (reservation_no),
    UNIQUE KEY uk_order_material (order_id, material_id) COMMENT '🔑同一工单同一物料只预留一次（幂等）',
    KEY idx_reservation_order (order_id),
    KEY idx_reservation_status_expire (status, expire_time) COMMENT '定时任务扫过期预留'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物料预留表（两段式核心）';

-- ---------------------------------------------------------------------
-- 领料单表：一次领料动作的凭证，预留转为实际消耗。
-- 🔑 uk_issue_no 是幂等键：防用户双击/网络重试导致重复领料。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS material_issue;
CREATE TABLE material_issue (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    issue_no       VARCHAR(64)  NOT NULL COMMENT '领料单号（幂等键，唯一）',
    order_id       BIGINT       NOT NULL COMMENT '工单',
    order_no       VARCHAR(64)           DEFAULT NULL COMMENT '工单号快照',
    warehouse_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '仓库',
    issue_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '1-领料 2-退料',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1-草稿 2-已确认 3-已作废',
    total_quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '本单合计数量',
    issue_time     DATETIME              DEFAULT NULL COMMENT '领料时间',
    operator_id    BIGINT                DEFAULT NULL COMMENT '领料人',
    operator_name  VARCHAR(64)           DEFAULT NULL COMMENT '领料人姓名',
    remark         VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_issue_no (issue_no) COMMENT '领料单号唯一：幂等最后防线',
    KEY idx_issue_order (order_id),
    KEY idx_issue_time (issue_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='领料单表';

-- ---------------------------------------------------------------------
-- 领料明细表：领料单的每一行。🔑 批次追溯的关键 —— 这里记录领了哪个批次。
-- 原表清单里没有这张表，但没有它正向/反向追溯都做不了。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS material_issue_item;
CREATE TABLE material_issue_item (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    issue_id       BIGINT        NOT NULL COMMENT '领料单',
    material_id    BIGINT        NOT NULL COMMENT '物料',
    material_code  VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    material_name  VARCHAR(128)           DEFAULT NULL COMMENT '物料名称快照',
    batch_no       VARCHAR(64)            DEFAULT NULL COMMENT '🔑领用的批次号（追溯关键）',
    quantity       DECIMAL(18,4) NOT NULL COMMENT '领用数量',
    reservation_id BIGINT                 DEFAULT NULL COMMENT '对应的预留记录',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_issue_item_issue (issue_id),
    KEY idx_issue_item_material_batch (material_id, batch_no),
    KEY idx_issue_item_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='领料明细表（批次追溯关键）';

-- ---------------------------------------------------------------------
-- 库存对账日志：定时对账发现的不一致记录。对账是分布式方案的最后防线。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS inventory_reconcile_log;
CREATE TABLE inventory_reconcile_log (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    material_id      BIGINT        NOT NULL COMMENT '物料',
    warehouse_id     BIGINT        NOT NULL COMMENT '仓库',
    inventory_qty    DECIMAL(18,4)          DEFAULT NULL COMMENT '库存表 quantity',
    ledger_qty       DECIMAL(18,4)          DEFAULT NULL COMMENT '流水账累加 quantity',
    diff             DECIMAL(18,4)          DEFAULT NULL COMMENT '差异',
    inventory_reserved DECIMAL(18,4)        DEFAULT NULL COMMENT '库存表 reserved_quantity',
    expected_reserved DECIMAL(18,4)         DEFAULT NULL COMMENT '按预留表推算的 reserved_quantity',
    abnormal_type    VARCHAR(32)            DEFAULT NULL COMMENT '异常类型 QUANTITY_DIFF / RESERVED_DIFF / NEGATIVE',
    handle_status    TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已修正 2-忽略',
    remark           VARCHAR(500)           DEFAULT NULL COMMENT '说明',
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_reconcile_material (material_id, warehouse_id),
    KEY idx_reconcile_status (handle_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='库存对账日志';
