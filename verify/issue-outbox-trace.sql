-- =====================================================================
-- SmartMES - material issue outbox -> trace edge -> backfill check
--
-- Purpose: prove the async trace chain end to end:
--   issue txn (deduct + ledger + outbox, one transaction)
--     -> scheduled delivery -> batch_relation edge (idempotent)
--     -> completion backfill fills the empty target
--     -> reconcile job catches anything missed
--
-- Run (all statements are read-only SELECTs):
--   "C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
--     -uroot -p --default-character-set=utf8mb4 -t \
--     < verify/issue-outbox-trace.sql > verify/trace-snapshot.txt
--
-- Note: smart_inventory and smart_quality are separate schemas on the
-- SAME MySQL instance, so cross-schema joins below are legal. They are
-- used here for verification only - application code never joins across
-- service boundaries.
-- =====================================================================

-- ---------------------------------------------------------------------
-- S1. Atomicity: every batch-bearing issue ledger row must already have
--     an outbox row (they were written in the SAME transaction).
--     Expect: 0 rows. Any row here means the reconcile job has work to
--     do (or someone edited the database by hand).
-- ---------------------------------------------------------------------
SELECT t.id AS tx_id, t.biz_no AS issue_no, m.order_id, t.batch_no, t.quantity
  FROM smart_inventory.inventory_transaction t
  JOIN smart_inventory.material_issue m ON m.issue_no = t.biz_no
  LEFT JOIN smart_inventory.material_issue_outbox o
    ON o.biz_key = CONCAT(m.order_id, ':', t.batch_no)
 WHERE t.transaction_type = 2
   AND t.batch_no IS NOT NULL AND t.batch_no <> ''
   AND o.id IS NULL;

-- ---------------------------------------------------------------------
-- S2. Delivery status: after the services have been up for a minute,
--     nothing should stay pending (status=0) or terminally failed
--     (status=2). Pending rows are normal while quality service is down
--     - that is the designed fallback, they will be delivered later.
-- ---------------------------------------------------------------------
SELECT status, COUNT(*) AS cnt, MAX(retry_count) AS max_retry
  FROM smart_inventory.material_issue_outbox
 GROUP BY status;

SELECT id, biz_key, issue_no, status, retry_count, next_retry_time, last_error
  FROM smart_inventory.material_issue_outbox
 WHERE status <> 1
 ORDER BY id DESC LIMIT 20;

-- ---------------------------------------------------------------------
-- S3. Consumer idempotency: uk_relation_consume must hold - one edge
--     per (order, source batch, relation type), no matter how many
--     times the outbox delivered the message.
--     Expect: 0 rows.
-- ---------------------------------------------------------------------
SELECT production_order_id, source_batch_no, relation_type, COUNT(*) AS times_seen
  FROM smart_quality.batch_relation
 WHERE production_order_id IS NOT NULL
 GROUP BY production_order_id, source_batch_no, relation_type
HAVING COUNT(*) > 1;

-- ---------------------------------------------------------------------
-- S4. Ledger -> edge completeness: every batch-bearing issue ledger row
--     must end up as a CONSUME edge in the quality schema.
--     Expect: 0 rows once delivery caught up (give it ~10s after issue).
--     A row here = message still in flight, or lost - check S2.
-- ---------------------------------------------------------------------
SELECT t.id AS tx_id, m.order_id, t.batch_no, ABS(t.quantity) AS issued_qty
  FROM smart_inventory.inventory_transaction t
  JOIN smart_inventory.material_issue m ON m.issue_no = t.biz_no
  LEFT JOIN smart_quality.batch_relation r
    ON r.production_order_id = m.order_id
   AND r.source_batch_no = t.batch_no
   AND r.relation_type = 'CONSUME'
   AND r.is_reversed = 0
 WHERE t.transaction_type = 2
   AND t.batch_no IS NOT NULL AND t.batch_no <> ''
   AND r.id IS NULL;

-- ---------------------------------------------------------------------
-- S5. Quantity fidelity: the edge quantity must equal what the outbox
--     recorded (what was actually issued), not a drifted copy.
--     Expect: 0 rows.
-- ---------------------------------------------------------------------
SELECT o.biz_key, o.quantity AS outbox_qty, r.quantity AS edge_qty
  FROM smart_inventory.material_issue_outbox o
  JOIN smart_quality.batch_relation r
    ON r.production_order_id = o.order_id
   AND r.source_batch_no = o.batch_no
   AND r.relation_type = 'CONSUME'
 WHERE o.quantity <> r.quantity;

-- ---------------------------------------------------------------------
-- S6. Backfill: empty target is BY DESIGN at issue time (product batch
--     does not exist yet), but once the order HAS a product batch
--     (batch_type=2), no CONSUME edge of that order may stay unbound.
--     Expect: 0 rows after the bind task ran (scan interval 10s).
-- ---------------------------------------------------------------------
SELECT r.id AS relation_id, r.production_order_id, r.source_batch_no,
       pb.batch_no AS product_batch_no
  FROM smart_quality.batch_relation r
  JOIN smart_quality.product_batch pb
    ON pb.production_order_id = r.production_order_id
   AND pb.batch_type = 2
 WHERE r.target_batch_id IS NULL
   AND r.relation_type = 'CONSUME'
   AND r.is_reversed = 0;

-- Bound edges must point at an existing product batch of the SAME order.
-- Expect: 0 rows.
SELECT r.id AS relation_id, r.production_order_id, r.target_batch_id
  FROM smart_quality.batch_relation r
  LEFT JOIN smart_quality.product_batch pb
    ON pb.id = r.target_batch_id
   AND pb.production_order_id = r.production_order_id
   AND pb.batch_type = 2
 WHERE r.target_batch_id IS NOT NULL
   AND r.relation_type = 'CONSUME'
   AND pb.id IS NULL;

-- ---------------------------------------------------------------------
-- S7. Recent chain at a glance: last 10 outbox messages joined to their
--     edge and backfill state, for eyeballing after a demo run.
-- ---------------------------------------------------------------------
SELECT o.id, o.biz_key, o.issue_no, o.status AS outbox_status,
       r.id AS edge_id, r.target_batch_no, r.create_time AS edge_time,
       o.sent_time
  FROM smart_inventory.material_issue_outbox o
  LEFT JOIN smart_quality.batch_relation r
    ON r.production_order_id = o.order_id
   AND r.source_batch_no = o.batch_no
   AND r.relation_type = 'CONSUME'
 ORDER BY o.id DESC LIMIT 10;