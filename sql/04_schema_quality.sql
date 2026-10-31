-- =====================================================================
-- 04 · 质量库 smart_quality
-- 表：product_batch / batch_relation / quality_inspection / quality_record
-- 本库承载 MES 最有辨识度的能力：批次正反向追溯（有向无环图）
-- =====================================================================

USE smart_quality;

-- ---------------------------------------------------------------------
-- 批次表：统一管理物料批次与产品批次，是追溯图的【节点】。
--
-- 为什么用一张表 + batch_type 而不是两张表：
--   结构高度相似（批次号/数量/状态），追溯时节点类型必须统一，BFS 才能通用。
--   代价：某些字段对某类型为 null（supplier_id 只对物料批次有意义）。
--   这是有意的取舍：表少、代码统一，代价是字段语义要注释清楚。
--
-- 🔑 supplier_id：物料批次的追溯源头。没有它，追溯到物料批次就断了。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS product_batch;
CREATE TABLE product_batch (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    batch_no            VARCHAR(64)   NOT NULL COMMENT '批次号（唯一）MB20250115001 物料 / PB20250115001 产品',
    batch_type          TINYINT       NOT NULL COMMENT '1-物料批次 2-产品批次',
    material_id         BIGINT                 DEFAULT NULL COMMENT '物料批次关联物料（产品批次为 null）',
    material_code       VARCHAR(64)            DEFAULT NULL COMMENT '物料编码快照',
    material_name       VARCHAR(128)           DEFAULT NULL COMMENT '物料名称快照',
    product_id          BIGINT                 DEFAULT NULL COMMENT '产品批次关联产品（物料批次为 null）',
    product_code        VARCHAR(64)            DEFAULT NULL COMMENT '产品编码快照',
    product_name        VARCHAR(128)           DEFAULT NULL COMMENT '产品名称快照',
    production_order_id BIGINT                 DEFAULT NULL COMMENT '产品批次关联工单（物料批次为 null）',
    order_no            VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    quantity            DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '批次数量',
    qualified_quantity  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '合格数量（产品批次）',
    defect_quantity     DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数量（产品批次）',
    supplier_id         BIGINT                 DEFAULT NULL COMMENT '🔑供应商（物料批次的追溯源头）',
    supplier_name       VARCHAR(128)           DEFAULT NULL COMMENT '供应商名称快照',
    inbound_time        DATETIME               DEFAULT NULL COMMENT '入库时间',
    quality_status      TINYINT       NOT NULL DEFAULT 0 COMMENT '质量状态 0-未检 1-合格 2-不合格 3-让步接收',
    status              TINYINT       NOT NULL DEFAULT 1 COMMENT '1-在库 2-已消耗 3-已用完 4-已报废',
    remark              VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_batch_no (batch_no),
    KEY idx_batch_type_status (batch_type, status),
    KEY idx_batch_material (material_id),
    KEY idx_batch_product (product_id),
    KEY idx_batch_order (production_order_id),
    KEY idx_batch_supplier (supplier_id) COMMENT '按供应商追溯（质量问题召回）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='批次表（物料批次 + 产品批次，追溯图节点）';

-- ---------------------------------------------------------------------
-- 批次关系链：记录"消耗了哪些批次、产出了哪个批次"。追溯图的【边】。
--
-- 为什么是【有向无环图】而不是树：
--   一个物料批次可能被多个工单消耗（1:N）
--   一个产品批次可能消耗多个物料批次（N:1）
--   所以是多对多的 DAG。
--   反向追溯 = 从 target 沿 source 方向 BFS；正向追溯 = 从 source 沿 target 方向 BFS。
--
-- 🔑 production_order_id：追溯结果要能定位"哪个工单出了问题"，方便召回整改。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS batch_relation;
CREATE TABLE batch_relation (
    id                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    source_batch_id     BIGINT        NOT NULL COMMENT '源批次（被消耗的物料批次）',
    source_batch_no     VARCHAR(64)            DEFAULT NULL COMMENT '源批次号快照',
    target_batch_id     BIGINT        NOT NULL COMMENT '目标批次（产出的产品批次）',
    target_batch_no     VARCHAR(64)            DEFAULT NULL COMMENT '目标批次号快照',
    production_order_id BIGINT                 DEFAULT NULL COMMENT '关联工单（追溯上下文）',
    order_no            VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    quantity            DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '消耗数量',
    relation_type       VARCHAR(32)   NOT NULL DEFAULT 'CONSUME' COMMENT '关系类型 CONSUME-领料消耗 OUTPUT-完工产出',
    is_reversed         TINYINT       NOT NULL DEFAULT 0 COMMENT '是否被退料冲正 0-否 1-是',
    create_time         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_relation_source (source_batch_id) COMMENT '正向追溯（原料→成品）',
    KEY idx_relation_target (target_batch_id) COMMENT '反向追溯（成品→原料）',
    KEY idx_relation_order (production_order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='批次关系链（追溯 DAG 的边）';

-- ---------------------------------------------------------------------
-- 质检单表：记录一次质量检验。是工序流转的【门禁】。
--
-- result 四态而非二态：
--   合格 / 让步接收 / 返工 / 不合格 —— 实际生产中轻微不良可让步接收，
--   部分不良可返工。只有合格/不合格二元状态无法表达这些业务分支。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS quality_inspection;
CREATE TABLE quality_inspection (
    id                 BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    inspection_no      VARCHAR(64)   NOT NULL COMMENT '质检单号（唯一）',
    inspection_type    TINYINT       NOT NULL COMMENT '1-来料检 2-首检 3-巡检 4-完工检 5-工序检',
    order_id           BIGINT                 DEFAULT NULL COMMENT '工单',
    order_no           VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    task_id            BIGINT                 DEFAULT NULL COMMENT '工序任务（工序检时填）',
    step_no            INT                    DEFAULT NULL COMMENT '工序号快照',
    step_name          VARCHAR(128)           DEFAULT NULL COMMENT '工序名称快照',
    batch_no           VARCHAR(64)            DEFAULT NULL COMMENT '批次',
    inspection_quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '检验数量（抽检时小于生产数量）',
    qualified_quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '合格数',
    defect_quantity    DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数',
    result             TINYINT                DEFAULT NULL COMMENT '1-合格 2-不合格 3-让步接收 4-返工',
    status             TINYINT       NOT NULL DEFAULT 1 COMMENT '1-待检 2-已完成',
    inspector_id       BIGINT                 DEFAULT NULL COMMENT '检验员',
    inspector_name     VARCHAR(64)            DEFAULT NULL COMMENT '检验员姓名',
    inspection_time    DATETIME               DEFAULT NULL COMMENT '检验时间',
    remark             VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inspection_no (inspection_no),
    KEY idx_inspection_order (order_id),
    KEY idx_inspection_task (task_id),
    KEY idx_inspection_result (result),
    KEY idx_inspection_batch (batch_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='质检单表';

-- ---------------------------------------------------------------------
-- 质检明细表：每个检验项的实测值。质量数据的原子单位。
--
-- 为什么检验项独立成表：
--   一个质检单有多个检验项（外观/尺寸/性能），不良代码需要聚合统计
--   （"本月尺寸超差占 40%"）。塞进主表就无法做多维分析。
-- 🔑 defect_code 存代码而非自由文本：统计需要【可聚合的代码】。
-- 本表只增不改（账本式）。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS quality_record;
CREATE TABLE quality_record (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    inspection_id  BIGINT        NOT NULL COMMENT '质检单',
    item_name      VARCHAR(128)  NOT NULL COMMENT '检验项名称（外观/尺寸/性能）',
    standard_value VARCHAR(128)           DEFAULT NULL COMMENT '标准值，如 10±0.1mm',
    actual_value   VARCHAR(255)           DEFAULT NULL COMMENT '实测值',
    is_qualified   TINYINT       NOT NULL DEFAULT 1 COMMENT '该项是否合格 1-是 0-否',
    defect_code    VARCHAR(32)            DEFAULT NULL COMMENT '不良代码（用于统计分析）',
    defect_name    VARCHAR(128)           DEFAULT NULL COMMENT '不良名称',
    defect_quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数量',
    remark         VARCHAR(255)           DEFAULT NULL COMMENT '备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_record_inspection (inspection_id),
    KEY idx_record_defect_code (defect_code) COMMENT '不良统计',
    KEY idx_record_qualified (is_qualified)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='质检明细表（只增不改）';

-- ---------------------------------------------------------------------
-- 不良品记录：报工不良数与质检不良项的统一出口，供质量分析。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS defect_record;
CREATE TABLE defect_record (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_no      VARCHAR(64)   NOT NULL COMMENT '不良记录号',
    source_type    VARCHAR(32)   NOT NULL COMMENT '来源 REPORT-报工 INSPECTION-质检',
    source_id      BIGINT                 DEFAULT NULL COMMENT '来源单据 ID',
    order_id       BIGINT                 DEFAULT NULL COMMENT '工单',
    order_no       VARCHAR(64)            DEFAULT NULL COMMENT '工单号快照',
    task_id        BIGINT                 DEFAULT NULL COMMENT '工序任务',
    step_name      VARCHAR(128)           DEFAULT NULL COMMENT '工序名称快照',
    batch_no       VARCHAR(64)            DEFAULT NULL COMMENT '批次号',
    defect_code    VARCHAR(32)            DEFAULT NULL COMMENT '不良代码',
    defect_name    VARCHAR(128)           DEFAULT NULL COMMENT '不良名称',
    defect_quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '不良数量',
    handle_type    TINYINT                DEFAULT NULL COMMENT '处理方式 1-返工 2-让步接收 3-报废 4-待处理',
    handle_status  TINYINT       NOT NULL DEFAULT 0 COMMENT '0-待处理 1-已处理',
    remark         VARCHAR(500)           DEFAULT NULL COMMENT '备注',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_defect_no (record_no),
    KEY idx_defect_order (order_id),
    KEY idx_defect_code (defect_code),
    KEY idx_defect_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='不良品记录表';
