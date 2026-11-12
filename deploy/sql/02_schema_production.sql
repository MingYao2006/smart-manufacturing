-- =====================================================================
-- 02 · 生产执行库 smart_production
-- 表：production_plan / production_order / work_task / production_report
--     order_status_log / mq_outbox
-- =====================================================================

USE smart_production;

-- ---------------------------------------------------------------------
-- 生产计划表：计划部门"打算生产什么、什么时候要"。工单的来源。
-- 计划与工单是 1:N（一个 1000 件计划常拆成多个批次）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS production_plan;
CREATE TABLE production_plan (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    plan_no         VARCHAR(64)   NOT NULL COMMENT '计划编号 PP20250115001',
    product_id      BIGINT        NOT NULL COMMENT '生产什么产品',
    product_code    VARCHAR(64)            DEFAULT NULL COMMENT '产品编码快照（反范式，避免跨库查）',
    product_name    VARCHAR(128)           DEFAULT NULL COMMENT '产品名称快照',
    plan_quantity   DECIMAL(18,4) NOT NULL COMMENT '计划数量',
    plan_start_date DATE                   DEFAULT NULL COMMENT '计划开始日',
    plan_end_date   DATE                   DEFAULT NULL COMMENT '计划完成日（交期），排产依据',
    status          TINYINT       NOT NULL DEFAULT 1 COMMENT '1-草稿 2-已确认 3-已转工单 4-已取消',
    remark          VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_by       BIGINT                 DEFAULT NULL COMMENT '创建人',
    update_by       BIGINT                 DEFAULT NULL COMMENT '更新人',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted      TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0-正常 1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_plan_no (plan_no, is_deleted) COMMENT '计划编号唯一（带 is_deleted 兼容逻辑删除）',
    KEY idx_plan_product_status (product_id, status),
    KEY idx_plan_end_date (plan_end_date) COMMENT '按交期排序/筛选'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='生产计划表';

-- ---------------------------------------------------------------------
-- 生产工单表：整个系统的核心单据。所有执行动作都挂在工单下。
--
-- 🔑 最重要的设计：必须存 bom_id 和 route_id（快照），而不只是 product_id。
--    工单创建那一刻"拍照"，之后 BOM 升级到 V2，正在生产的工单仍用 V1。
--    这是制造业铁律：历史工单的物料需求必须可追溯。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS production_order;
CREATE TABLE production_order (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_no            VARCHAR(64)   NOT NULL COMMENT '工单号 WO20250115001（唯一，含 is_deleted）',
    plan_id             BIGINT                 DEFAULT NULL COMMENT '来源计划（null=直接创建）',
    product_id          BIGINT        NOT NULL COMMENT '生产什么产品',
    product_code        VARCHAR(64)            DEFAULT NULL COMMENT '产品编码快照',
    product_name        VARCHAR(128)           DEFAULT NULL COMMENT '产品名称快照',
    spec                VARCHAR(255)           DEFAULT NULL COMMENT '规格快照',
    unit                VARCHAR(16)            DEFAULT NULL COMMENT '单位快照',
    bom_id              BIGINT        NOT NULL COMMENT '🔑锁定的 BOM 版本（快照）',
    bom_version         VARCHAR(32)            DEFAULT NULL COMMENT '锁定的 BOM 版本号快照',
    route_id            BIGINT        NOT NULL COMMENT '🔑锁定的工艺路线版本（快照）',
    route_version       VARCHAR(32)            DEFAULT NULL COMMENT '锁定的工艺路线版本号快照',
    plan_quantity       DECIMAL(18,4) NOT NULL COMMENT '计划生产数量',
    completed_quantity  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已完工数量（由报工重算驱动，不手工维护）',
    qualified_quantity  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '合格数量（由报工重算驱动）',
    defect_quantity     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数量（由报工重算驱动）',
    progress            INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100（按已完成工序数重算）',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '状态机 1-草稿 2-已审核 3-已下达 4-生产中 5-暂停 6-已完工 7-已关闭 8-已取消',
    plan_start_time     DATETIME               DEFAULT NULL COMMENT '计划开始',
    plan_end_time       DATETIME               DEFAULT NULL COMMENT '计划完成（交期）',
    actual_start_time   DATETIME               DEFAULT NULL COMMENT '实际开始（首道工序开始时写入）',
    actual_end_time     DATETIME               DEFAULT NULL COMMENT '实际完成',
    remark              VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_by           BIGINT                 DEFAULT NULL COMMENT '创建人',
    update_by           BIGINT                 DEFAULT NULL COMMENT '更新人',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no, is_deleted) COMMENT '工单号唯一，唯一索引是单号重复的最后一道防线',
    KEY idx_order_status (status) COMMENT '按状态筛选是看板核心查询',
    KEY idx_order_product_status (product_id, status),
    KEY idx_order_plan_end_time (plan_end_time) COMMENT '按交期排序',
    KEY idx_order_plan_id (plan_id),
    KEY idx_order_product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='生产工单表（系统核心单据）';

-- ---------------------------------------------------------------------
-- 工序任务表：工单下达后按工艺路线展开的可执行任务单元，工人实操的对象。
--
-- 🔑 uk_order_step (order_id, step_no)：防重复生成工序任务的最后一道防线。
--    工单重复下达或 MQ 消息重投时，没有它会生成两套任务 → 报工翻倍。
--
-- 🔑 reported_quantity 存表而不是每次 SUM(报工表)：
--    超报校验需要原子性，条件更新 WHERE reported + ? <= plan 才能保证并发安全。
--    这是"冗余换并发安全"的典型取舍。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS work_task;
CREATE TABLE work_task (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id            BIGINT        NOT NULL COMMENT '所属工单',
    order_no            VARCHAR(64)            DEFAULT NULL COMMENT '冗余工单号（避免查询 JOIN）',
    product_id          BIGINT                 DEFAULT NULL COMMENT '产品（冗余，便于看板按产品聚合）',
    step_no             INT           NOT NULL COMMENT '工序号（快照），决定执行顺序',
    step_name           VARCHAR(128)  NOT NULL COMMENT '工序名称（快照）',
    work_center         VARCHAR(64)            DEFAULT NULL COMMENT '工作中心（快照）',
    standard_hours      DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '标准工时（快照）',
    plan_quantity       DECIMAL(18,4) NOT NULL COMMENT '计划数量（快照自工单）',
    reported_quantity   DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '累计已报工数量，用于超报校验',
    qualified_quantity  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '累计合格数',
    defect_quantity     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '累计不良数',
    actual_hours        DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '实际工时（报工累加）',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1-待开始 2-进行中 3-暂停 4-已完成 5-已跳过 6-待质检',
    is_inspection       TINYINT       NOT NULL DEFAULT 0 COMMENT '是否质检点（快照），1=完成后必须质检合格',
    operator_id         BIGINT                 DEFAULT NULL COMMENT '派给谁',
    operator_name       VARCHAR(64)            DEFAULT NULL COMMENT '操作工姓名快照',
    start_time          DATETIME               DEFAULT NULL COMMENT '实际开始时间',
    end_time            DATETIME               DEFAULT NULL COMMENT '实际结束时间',
    create_by           BIGINT                 DEFAULT NULL COMMENT '创建人',
    update_by           BIGINT                 DEFAULT NULL COMMENT '更新人',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_step (order_id, step_no) COMMENT '🔑防重复生成工序任务的关键索引',
    KEY idx_task_order (order_id),
    KEY idx_task_status_operator (status, operator_id) COMMENT '查"我的待办任务"',
    KEY idx_task_work_center_status (work_center, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工序任务表';

-- ---------------------------------------------------------------------
-- 生产报工表：每次报工的明细账。
--
-- 🔑 report_no 是幂等键：用户双击/网络重试会产生重复提交，
--    没有它"点两次"就会数量翻倍。
-- 🔑 defect_quantity 单独记录：质量数据来源，合格率计算依据。
--
-- 报工是明细账，work_task 是汇总账；有明细才能追溯"谁在什么时候报了多少"。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS production_report;
CREATE TABLE production_report (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    report_no           VARCHAR(64)   NOT NULL COMMENT '报工单号（幂等键，唯一）',
    order_id            BIGINT        NOT NULL COMMENT '工单',
    order_no            VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    task_id             BIGINT        NOT NULL COMMENT '工序任务',
    step_no             INT                    DEFAULT NULL COMMENT '工序号快照',
    step_name           VARCHAR(128)           DEFAULT NULL COMMENT '工序名称快照',
    report_quantity     DECIMAL(18,4) NOT NULL COMMENT '本次报工数量',
    qualified_quantity  DECIMAL(18,4) NOT NULL COMMENT '合格数',
    defect_quantity     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数（校验：合格+不良=报工）',
    actual_hours        DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '实际工时',
    operator_id         BIGINT                 DEFAULT NULL COMMENT '报工人',
    operator_name       VARCHAR(64)            DEFAULT NULL COMMENT '报工人姓名快照',
    report_time         DATETIME      NOT NULL COMMENT '报工时间',
    remark              VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_no (report_no) COMMENT '报工单号唯一：幂等最后防线',
    KEY idx_report_task (task_id),
    KEY idx_report_order (order_id),
    KEY idx_report_operator_time (operator_id, report_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='生产报工表';

-- ---------------------------------------------------------------------
-- 工单状态流转日志：谁、何时、从什么状态到什么状态。
-- 只增不改，是状态机可追溯性的保证。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS order_status_log;
CREATE TABLE order_status_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    order_id       BIGINT       NOT NULL COMMENT '工单',
    order_no       VARCHAR(64)           DEFAULT NULL COMMENT '工单号快照',
    from_status    TINYINT               DEFAULT NULL COMMENT '原状态（首次为空）',
    from_status_desc VARCHAR(32)         DEFAULT NULL COMMENT '原状态描述',
    to_status      TINYINT      NOT NULL COMMENT '目标状态',
    to_status_desc VARCHAR(32)           DEFAULT NULL COMMENT '目标状态描述',
    reason         VARCHAR(255)          DEFAULT NULL COMMENT '操作原因',
    operator_id    BIGINT                DEFAULT NULL COMMENT '操作人',
    operator_name  VARCHAR(64)           DEFAULT NULL COMMENT '操作人姓名',
    operate_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_log_order (order_id),
    KEY idx_log_operate_time (operate_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工单状态流转日志（只增不改）';

-- ---------------------------------------------------------------------
-- 本地消息表（outbox）：解决"业务数据提交成功、发 MQ 前宕机导致消息丢失"。
--
-- 🔑 业务写入与 outbox 记录在【同一事务】提交，之后由定时任务投递并等 publisher confirm。
-- 🔑 uk_biz_key_exchange 保证同一条业务事件不会被重复写入 outbox。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS mq_outbox;
CREATE TABLE mq_outbox (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    biz_key          VARCHAR(128)  NOT NULL COMMENT '业务幂等键，如 report:123:order-progress',
    exchange_name    VARCHAR(128)  NOT NULL COMMENT '目标交换机',
    routing_key      VARCHAR(128)  NOT NULL COMMENT '路由键',
    payload          TEXT          NOT NULL COMMENT '消息体 JSON',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待投递 1-已投递 2-投递失败(终态) 3-路由失败退回',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '已重试次数',
    next_retry_time  DATETIME               DEFAULT NULL COMMENT '下次重试时间（指数退避）',
    last_error       VARCHAR(500)           DEFAULT NULL COMMENT '最后一次失败原因',
    create_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    sent_time        DATETIME               DEFAULT NULL COMMENT '投递成功时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_biz (biz_key, exchange_name) COMMENT '同一业务事件只写一条 outbox',
    KEY idx_outbox_status_retry (status, next_retry_time) COMMENT '定时任务扫描待投递'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='本地消息表（保证消息不丢）';

-- ---------------------------------------------------------------------
-- 消费幂等记录表：有副作用的消费（非重算类）用唯一键硬拦重复。
-- 相比 Redis SETNX，数据库唯一索引不会因 Redis 丢 key/过期而失效。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS mq_consume_log;
CREATE TABLE mq_consume_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    consume_key  VARCHAR(191) NOT NULL COMMENT '消费幂等键',
    topic        VARCHAR(128)          DEFAULT NULL COMMENT '消息主题/队列',
    msg_id       VARCHAR(128)          DEFAULT NULL COMMENT '原始消息 ID',
    result       VARCHAR(32)           DEFAULT NULL COMMENT '处理结果',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_consume_key (consume_key) COMMENT '同一消息只能成功处理一次',
    KEY idx_consume_topic (topic),
    KEY idx_consume_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='消费幂等记录表';
