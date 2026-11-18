-- =====================================================================
-- SmartMES - incremental migration: 进货审批单（补料申请）
--
-- For an ALREADY RUNNING database. 03_schema_inventory.sql uses DROP TABLE
-- and would wipe inventory data - do NOT re-run it on a live database.
-- This script only adds the new table, and is safe to re-run (IF NOT EXISTS).
--
-- Run:
--   "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
--     -uroot -p --default-character-set=utf8mb4 -t \
--     < sql/08_migrate_purchase_request.sql
--
-- After running: restart smart-inventory so the new mapper/controller and
-- smart-gateway so the new RBAC rules pick up the feature.
-- =====================================================================

CREATE TABLE IF NOT EXISTS smart_inventory.purchase_request (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    request_no      VARCHAR(64)   NOT NULL COMMENT '进货审批单号（PR+日期+序列）',
    material_id     BIGINT        NOT NULL COMMENT '申请进货的物料',
    material_code   VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    material_name   VARCHAR(128)           DEFAULT NULL COMMENT '物料名称快照',
    unit            VARCHAR(16)            DEFAULT NULL COMMENT '单位快照',
    warehouse_id    BIGINT        NOT NULL DEFAULT 1 COMMENT '入库仓库',
    quantity        DECIMAL(18,4) NOT NULL COMMENT '申请进货数量（>0）',
    batch_no        VARCHAR(64)            DEFAULT NULL COMMENT '入库批次号；为空时审批通过由服务端生成',
    reason          VARCHAR(500)           DEFAULT NULL COMMENT '申请原因（如低于安全库存）',
    supplier_name   VARCHAR(128)           DEFAULT NULL COMMENT '供应商（选填）',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1-待审批 2-已通过(已入库) 3-已驳回 4-已撤销',
    applicant_id    BIGINT                 DEFAULT NULL COMMENT '申请人（仓管）ID',
    applicant_name  VARCHAR(64)            DEFAULT NULL COMMENT '申请人姓名快照',
    applicant_role  VARCHAR(32)            DEFAULT NULL COMMENT '申请人角色快照',
    approver_id     BIGINT                 DEFAULT NULL COMMENT '审批人 ID',
    approver_name   VARCHAR(64)            DEFAULT NULL COMMENT '审批人姓名快照',
    approver_role   VARCHAR(32)            DEFAULT NULL COMMENT '审批人角色（ADMIN / PLANNER）',
    approve_comment VARCHAR(500)           DEFAULT NULL COMMENT '审批意见',
    approve_time    DATETIME               DEFAULT NULL COMMENT '审批时间',
    inbound_tx_id   BIGINT                 DEFAULT NULL COMMENT '审批通过时写入的库存流水 ID（审计链路）',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_request_no (request_no),
    KEY idx_purchase_status (status, create_time),
    KEY idx_purchase_material (material_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='进货审批单（仓管申请，管理员/计划员或签审批，通过自动入库）';

SHOW CREATE TABLE smart_inventory.purchase_request;
