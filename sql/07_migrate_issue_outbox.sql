-- =====================================================================
-- SmartMES - incremental migration: issue outbox + trace edge idempotency
--
-- For an ALREADY RUNNING database. The full schema scripts (03/04) use
-- DROP TABLE and would wipe data - do NOT re-run them. This script only
-- adds what the new async trace chain needs, and is safe to re-run.
--
-- Run:
--   "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
--     -uroot -p --default-character-set=utf8mb4 -t \
--     < sql/07_migrate_issue_outbox.sql
--
-- After running: restart smart-inventory and smart-quality so the new
-- mappers/scheduled tasks pick up the tables.
-- =====================================================================

-- ---------------------------------------------------------------------
-- M0. Pre-check: the new unique key refuses duplicate edges. If this
--     returns rows, dedupe them by hand FIRST (keep the lowest id),
--     otherwise M3 will fail. Expected: empty result.
-- ---------------------------------------------------------------------
SELECT production_order_id, source_batch_no, relation_type, COUNT(*) AS cnt
  FROM smart_quality.batch_relation
 WHERE production_order_id IS NOT NULL
 GROUP BY production_order_id, source_batch_no, relation_type
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------
-- M1. Outbox table in smart_inventory (same DDL as 03_schema_inventory,
--     but CREATE IF NOT EXISTS instead of DROP).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS smart_inventory.material_issue_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_key          VARCHAR(191)  NOT NULL COMMENT '幂等键 orderId:batchNo',
    order_id         BIGINT        NOT NULL COMMENT '工单 ID（回填 target 的依据）',
    order_no         VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    issue_no         VARCHAR(64)            DEFAULT NULL COMMENT '领料单号',
    material_id      BIGINT                 DEFAULT NULL COMMENT '物料',
    material_code    VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    batch_no         VARCHAR(64)   NOT NULL COMMENT '领用的物料批次号（追溯起点）',
    quantity         DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '领用数量',
    payload          TEXT          NOT NULL COMMENT '待投递消息体 JSON',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待投递 1-已投递 2-投递失败(终态)',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time  DATETIME               DEFAULT NULL COMMENT '下次重试时间（指数退避）',
    last_error       VARCHAR(500)           DEFAULT NULL COMMENT '最后一次失败原因',
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    sent_time        DATETIME               DEFAULT NULL COMMENT '投递成功时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_biz (biz_key),
    KEY idx_outbox_status_retry (status, next_retry_time),
    KEY idx_outbox_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='领料批次关系 outbox（同事务写，异步建边）';

-- ---------------------------------------------------------------------
-- M2. Allow empty target at issue time (product batch not born yet).
--     MODIFY is idempotent - running it twice is harmless.
-- ---------------------------------------------------------------------
ALTER TABLE smart_quality.batch_relation
    MODIFY target_batch_id BIGINT DEFAULT NULL
    COMMENT '目标批次（产出的产品批次）；领料阶段产品批次未生成，为空待完工回填';

-- ---------------------------------------------------------------------
-- M3. Consumer idempotency key: one edge per (order, source batch, type).
--     Guarded so a re-run does not error on "duplicate key name".
-- ---------------------------------------------------------------------
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = 'smart_quality'
       AND table_name = 'batch_relation'
       AND index_name = 'uk_relation_consume'
);
SET @ddl = IF(@idx_exists = 0,
    'ALTER TABLE smart_quality.batch_relation
       ADD UNIQUE KEY uk_relation_consume (production_order_id, source_batch_no, relation_type)',
    'SELECT ''uk_relation_consume already exists'' AS note');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------------
-- M4. Sanity: show the resulting structure.
-- ---------------------------------------------------------------------
SHOW CREATE TABLE smart_inventory.material_issue_outbox;
SHOW INDEX FROM smart_quality.batch_relation;