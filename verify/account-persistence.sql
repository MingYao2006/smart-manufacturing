-- =====================================================================
-- SmartMES - account -> database persistence check
--
-- Purpose: prove that an operation done in the browser workbench really
--          wrote rows into MySQL, and that the row records WHO did it.
--
-- Run (all statements are read-only SELECTs):
--   "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
--     -uroot -p --default-character-set=utf8mb4 -t \
--     < verify/account-persistence.sql > verify/snapshot.txt
--
-- Conventions used below:
--   * every test order is created with remark = 'PERSIST-TEST'
--     so it can be found and cleaned up in one shot (section S5)
--   * user ids: 1 admin / 2 planner / 3 warehouse / 4 operator / 5 inspector
--   * Chinese column values may look garbled in cmd.exe; redirect to a file
--     and open it with an UTF-8 editor instead of reading it in the console
-- =====================================================================

-- ---------------------------------------------------------------------
-- S0. Baseline snapshot: one row per write-target table.
--     Take this BEFORE each UI action, then take it again AFTER the action.
--     "rows_now" must grow (or "last_write" must move) for a real write.
-- ---------------------------------------------------------------------
SELECT 'production_order'          AS tbl, COUNT(*) AS rows_now, MAX(id) AS max_id, MAX(create_time) AS last_write FROM smart_production.production_order
UNION ALL SELECT 'work_task',            COUNT(*), MAX(id), MAX(create_time) FROM smart_production.work_task
UNION ALL SELECT 'order_status_log',     COUNT(*), MAX(id), MAX(operate_time) FROM smart_production.order_status_log
UNION ALL SELECT 'production_report',    COUNT(*), MAX(id), MAX(create_time) FROM smart_production.production_report
UNION ALL SELECT 'mq_outbox',            COUNT(*), MAX(id), MAX(create_time) FROM smart_production.mq_outbox
UNION ALL SELECT 'mq_consume_log',       COUNT(*), MAX(id), MAX(create_time) FROM smart_production.mq_consume_log
UNION ALL SELECT 'quality_inspection',   COUNT(*), MAX(id), MAX(create_time) FROM smart_quality.quality_inspection
UNION ALL SELECT 'quality_record',       COUNT(*), MAX(id), MAX(create_time) FROM smart_quality.quality_record
UNION ALL SELECT 'defect_record',        COUNT(*), MAX(id), MAX(create_time) FROM smart_quality.defect_record
UNION ALL SELECT 'product_batch',        COUNT(*), MAX(id), MAX(create_time) FROM smart_quality.product_batch
UNION ALL SELECT 'batch_relation',       COUNT(*), MAX(id), MAX(create_time) FROM smart_quality.batch_relation
UNION ALL SELECT 'material_reservation', COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.material_reservation
UNION ALL SELECT 'material_issue',       COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.material_issue
UNION ALL SELECT 'material_issue_item',  COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.material_issue_item
UNION ALL SELECT 'inventory_transaction',COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.inventory_transaction
UNION ALL SELECT 'inventory_reconcile_log', COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.inventory_reconcile_log
UNION ALL SELECT 'material_inventory',   COUNT(*), MAX(id), MAX(update_time) FROM smart_inventory.material_inventory
UNION ALL SELECT 'material_issue_outbox', COUNT(*), MAX(id), MAX(create_time) FROM smart_inventory.material_issue_outbox;

-- ---------------------------------------------------------------------
-- S1. Attribution view: last 25 writes and the account behind them.
--     create_by / operator_id / inspector_id must equal the logged-in
--     user id, which is the gateway-injected X-User-Id header.
--     (user id 0 = SYSTEM fallback => means the header never arrived,
--      i.e. the call bypassed the gateway or auth was disabled.)
-- ---------------------------------------------------------------------
SELECT 'order' AS src, id, order_no AS ref, create_by AS by_user, update_by AS upd_user, create_time
  FROM smart_production.production_order ORDER BY id DESC LIMIT 8;
SELECT 'task' AS src, id, order_no AS ref, operator_id AS by_user, create_by AS upd_user, create_time
  FROM smart_production.work_task ORDER BY id DESC LIMIT 8;
SELECT 'status_log' AS src, id, order_no AS ref, operator_id AS by_user, operator_name AS by_name, operate_time AS create_time
  FROM smart_production.order_status_log ORDER BY id DESC LIMIT 8;
SELECT 'report' AS src, id, report_no AS ref, operator_id AS by_user, operator_name AS by_name, create_time
  FROM smart_production.production_report ORDER BY id DESC LIMIT 8;
SELECT 'inspection' AS src, id, inspection_no AS ref, inspector_id AS by_user, inspector_name AS by_name, create_time
  FROM smart_quality.quality_inspection ORDER BY id DESC LIMIT 8;
SELECT 'stock_txn' AS src, id, biz_no AS ref, operator_id AS by_user, operator_name AS by_name, create_time
  FROM smart_inventory.inventory_transaction ORDER BY id DESC LIMIT 8;
SELECT 'issue' AS src, id, issue_no AS ref, operator_id AS by_user, operator_name AS by_name, create_time
  FROM smart_inventory.material_issue ORDER BY id DESC LIMIT 8;

-- ---------------------------------------------------------------------
-- S2. Full lifecycle of the test orders in one pass.
--     Expect: status walks 1->2->3->4->(5)->6, one order_status_log row
--     per hop, work_task rows appear only after release, and every hop
--     carries the operator who clicked it.
-- ---------------------------------------------------------------------
SELECT o.id, o.order_no, o.status, o.plan_quantity, o.completed_quantity,
       o.qualified_quantity, o.defect_quantity, o.progress,
       o.create_by, o.update_by, o.remark
  FROM smart_production.production_order o
 WHERE o.remark = 'PERSIST-TEST'
 ORDER BY o.id DESC;

SELECT l.order_no, l.from_status, l.to_status, l.operator_id, l.operator_name, l.operate_time
  FROM smart_production.order_status_log l
 WHERE l.order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST')
 ORDER BY l.id;

SELECT t.order_no, t.step_no, t.step_name, t.status, t.is_inspection,
       t.reported_quantity, t.qualified_quantity, t.operator_id, t.operator_name, t.create_by
  FROM smart_production.work_task t
 WHERE t.order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST')
 ORDER BY t.order_id, t.step_no;

-- ---------------------------------------------------------------------
-- S3. Report idempotency: the same report_no must exist exactly once,
--     and completed_quantity on the order must equal the sum of reports
--     (replaying an MQ message must not double-count progress).
-- ---------------------------------------------------------------------
SELECT report_no, COUNT(*) AS times_seen
  FROM smart_production.production_report
 GROUP BY report_no HAVING COUNT(*) > 1;

SELECT o.id, o.order_no, o.completed_quantity AS order_side,
       IFNULL(SUM(r.report_quantity), 0)      AS report_side,
       o.completed_quantity - IFNULL(SUM(r.report_quantity), 0) AS diff_should_be_zero
  FROM smart_production.production_order o
  LEFT JOIN smart_production.production_report r ON r.order_id = o.id
 WHERE o.remark = 'PERSIST-TEST'
 GROUP BY o.id, o.order_no, o.completed_quantity;

-- ---------------------------------------------------------------------
-- S4. Async chain: transactional outbox -> RabbitMQ -> consumer.
--     Expect: every outbox row for the test order reaches status = 1
--     (sent) and the consumer wrote one mq_consume_log row per message.
--     If RabbitMQ is down the outbox rows stay status = 0 and business
--     data is still correct - that is the designed fallback.
-- ---------------------------------------------------------------------
SELECT o.order_no, x.biz_key, x.routing_key, x.status, x.retry_count,
       x.sent_time, x.last_error
  FROM smart_production.mq_outbox x
  JOIN smart_production.production_order o
    ON x.payload LIKE CONCAT('%', o.order_no, '%')
 WHERE o.remark = 'PERSIST-TEST'
 ORDER BY x.id;

SELECT consume_key, topic, result, create_time
  FROM smart_production.mq_consume_log ORDER BY id DESC LIMIT 10;

-- ---------------------------------------------------------------------
-- S5. Inventory consistency: the three-quantity model must never go
--     negative, and every quantity change must have a matching
--     inventory_transaction row (that is what proves the write path,
--     not just an UPDATE of a number).
-- ---------------------------------------------------------------------
SELECT material_id, material_code, warehouse_id, quantity, reserved_quantity,
       quantity - reserved_quantity AS available, version, update_time
  FROM smart_inventory.material_inventory
 ORDER BY update_time DESC LIMIT 10;

SELECT material_id, warehouse_id, quantity, reserved_quantity
  FROM smart_inventory.material_inventory
 WHERE quantity < 0 OR reserved_quantity < 0 OR reserved_quantity > quantity;

SELECT transaction_no, material_code, transaction_type, quantity,
       before_quantity, after_quantity, before_reserved, after_reserved,
       biz_type, biz_no, operator_id, operator_name
  FROM smart_inventory.inventory_transaction ORDER BY id DESC LIMIT 12;

-- ---------------------------------------------------------------------
-- S6. Negative cases (expected: NO new row).
--     Run S0, trigger the illegal action in the UI, run S0 again -
--     every rows_now value must be identical.
--       a) illegal status jump, e.g. DRAFT(1) -> COMPLETED(6)
--       b) start a work task that is not dispatched / previous step open
--       c) report more than the remaining plan quantity
--       d) reserve more material than available (over-issue guard)
--       e) duplicate submit (double click) on create order / report
--     Also confirm the API returned an error, not a silent success:
--       HTTP 409 with code 40900 for CAS conflicts / illegal transition.
-- ---------------------------------------------------------------------
SELECT COUNT(*) AS orders_stuck_in_impossible_state
  FROM smart_production.production_order
 WHERE completed_quantity > plan_quantity
    OR qualified_quantity > completed_quantity
    OR defect_quantity    > completed_quantity
    OR (status = 8 AND is_deleted <> 0);

SELECT COUNT(*) AS tasks_reported_beyond_plan
  FROM smart_production.work_task
 WHERE reported_quantity > plan_quantity;

-- ---------------------------------------------------------------------
-- S7. Cleanup - commented out on purpose. Uncomment after reviewing S2,
--     and delete children before parents. Only touches remark rows.
-- ---------------------------------------------------------------------
-- SET @ids = (SELECT GROUP_CONCAT(id) FROM smart_production.production_order WHERE remark = 'PERSIST-TEST');
-- SELECT @ids;   -- eyeball this list before running the deletes
-- DELETE FROM smart_production.order_status_log     WHERE order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST');
-- DELETE FROM smart_production.production_report    WHERE order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST');
-- DELETE FROM smart_production.work_task            WHERE order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST');
-- DELETE FROM smart_inventory.material_reservation  WHERE order_id IN (SELECT id FROM smart_production.production_order WHERE remark = 'PERSIST-TEST');
-- DELETE FROM smart_production.production_order     WHERE remark = 'PERSIST-TEST';
-- SELECT 'cleanup done' AS note;
