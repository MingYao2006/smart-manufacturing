-- =====================================================================
-- 01 · 基础资料库 smart_product
-- 表：product / material / bom / bom_item / process_route / process_step
-- 执行顺序：第一个（其他库的脚本依赖这里的演示数据）
-- =====================================================================

USE smart_product;

-- ---------------------------------------------------------------------
-- 产品表：定义"能生产什么"。BOM 与工艺路线的挂载点，工单的对象。
-- 删除策略：物理删除（见 00_conventions.sql 第 6 条）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS product;
CREATE TABLE product (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_code  VARCHAR(64)  NOT NULL COMMENT '产品编码，对外唯一标识，工单/BOM 都引用它',
    product_name  VARCHAR(128) NOT NULL COMMENT '产品名称',
    spec          VARCHAR(255)          DEFAULT NULL COMMENT '规格型号：同编码不同规格在制造业很常见',
    unit          VARCHAR(16)  NOT NULL DEFAULT '个' COMMENT '计量单位：报工和库存都依赖它，不能省',
    category      VARCHAR(64)           DEFAULT NULL COMMENT '产品分类（成品/半成品）',
    safety_stock  DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '安全库存，用于库存预警',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用（停用产品不允许建工单）',
    remark        VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    create_by     BIGINT                DEFAULT NULL COMMENT '创建人',
    update_by     BIGINT                DEFAULT NULL COMMENT '更新人',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_code (product_code) COMMENT '产品编码唯一：业务约束的硬保证',
    KEY idx_product_status (status),
    KEY idx_product_name (product_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='产品表（成品/半成品）';

-- ---------------------------------------------------------------------
-- 物料表：定义"用什么生产"。被消耗对象，库存与领料的主体。
-- 删除策略：物理删除
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS material;
CREATE TABLE material (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    material_code     VARCHAR(64)  NOT NULL COMMENT '物料编码，唯一',
    material_name     VARCHAR(128) NOT NULL COMMENT '物料名称',
    spec              VARCHAR(255)          DEFAULT NULL COMMENT '规格',
    unit              VARCHAR(16)  NOT NULL DEFAULT '个' COMMENT '单位',
    material_type     VARCHAR(32)  NOT NULL DEFAULT 'RAW' COMMENT '类型 RAW-原料 AUX-辅料 PACK-包装：领料策略不同',
    safety_stock      DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '安全库存，低于此值预警',
    is_batch_managed  TINYINT      NOT NULL DEFAULT 1 COMMENT '是否批次管理：追溯的前提，只有批次管理物料能追溯供应商',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    remark            VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    create_by         BIGINT                DEFAULT NULL COMMENT '创建人',
    update_by         BIGINT                DEFAULT NULL COMMENT '更新人',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_material_code (material_code) COMMENT '物料编码唯一',
    KEY idx_material_type (material_type),
    KEY idx_material_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='物料表（原材料/辅料/包装）';

-- ---------------------------------------------------------------------
-- BOM 主表：定义"一个产品由哪些物料按什么用量组成"。
-- 版本化对象，同一产品同时只能有一个"生效"版本（status=2）。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS bom;
CREATE TABLE bom (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id     BIGINT       NOT NULL COMMENT '关联产品',
    bom_version    VARCHAR(32)  NOT NULL COMMENT '版本号 V1/V2…（同产品唯一）',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1-草稿 2-生效 3-失效（同产品只能一个 2）',
    effective_date DATE                  DEFAULT NULL COMMENT '生效日期，配合 status 使用',
    remark         VARCHAR(500)          DEFAULT NULL COMMENT '变更说明（为什么升版本）',
    create_by      BIGINT                DEFAULT NULL COMMENT '创建人',
    update_by      BIGINT                DEFAULT NULL COMMENT '更新人',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_version (product_id, bom_version) COMMENT '同产品版本号唯一',
    KEY idx_bom_product_status (product_id, status) COMMENT '查"当前生效 BOM"的高频查询'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='BOM 主表（版本化）';

-- ---------------------------------------------------------------------
-- BOM 明细：记录每一行 —— 用什么物料、用多少、损耗多少
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS bom_item;
CREATE TABLE bom_item (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    bom_id      BIGINT        NOT NULL COMMENT '关联 BOM 主表',
    material_id BIGINT        NOT NULL COMMENT '关联物料',
    quantity    DECIMAL(18,4) NOT NULL COMMENT '单位用量（生产 1 个产品需要多少该物料），支持小数如 0.05kg',
    loss_rate   DECIMAL(6,4)  NOT NULL DEFAULT 0.0000 COMMENT '损耗率 0.02=2%：制造业必需，投料必须多备',
    seq_no      INT           NOT NULL DEFAULT 1 COMMENT '序号，用于展示排序',
    remark      VARCHAR(255)           DEFAULT NULL COMMENT '备注',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bom_material (bom_id, material_id) COMMENT '同一 BOM 里同一物料只出现一行，合并计算',
    KEY idx_bom_item_bom (bom_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='BOM 明细表';

-- ---------------------------------------------------------------------
-- 工艺路线主表：定义"这个产品要经过哪些工序"。与 BOM 并列的"另一份配方"。
-- 同样版本化：老工单必须按老工艺执行。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS process_route;
CREATE TABLE process_route (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    product_id     BIGINT       NOT NULL COMMENT '关联产品',
    route_version  VARCHAR(32)  NOT NULL COMMENT '版本号',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1-草稿 2-生效 3-失效',
    effective_date DATE                  DEFAULT NULL COMMENT '生效日期',
    remark         VARCHAR(500)          DEFAULT NULL COMMENT '变更说明',
    create_by      BIGINT                DEFAULT NULL COMMENT '创建人',
    update_by      BIGINT                DEFAULT NULL COMMENT '更新人',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_route_version (product_id, route_version) COMMENT '同产品版本号唯一',
    KEY idx_route_product_status (product_id, status) COMMENT '查"当前生效工艺路线"'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工艺路线主表（版本化）';

-- ---------------------------------------------------------------------
-- 工序步骤：工艺路线的每一道工序，是 work_task 的模板。
-- step_no 用 10/20/30 而不是 1/2/3 —— 留出插入空间（制造业惯例）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS process_step;
CREATE TABLE process_step (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    route_id        BIGINT        NOT NULL COMMENT '关联工艺路线',
    step_no         INT           NOT NULL COMMENT '工序号 10/20/30：留间隔便于插入新工序，决定执行顺序',
    step_name       VARCHAR(128)  NOT NULL COMMENT '工序名称（下料/组装/测试）',
    work_center     VARCHAR(64)            DEFAULT NULL COMMENT '工作中心（车间/产线），用于派工与产能统计',
    standard_hours  DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '标准工时，用于产能测算和实际工时对比',
    is_inspection   TINYINT       NOT NULL DEFAULT 0 COMMENT '是否质检点：1=完成后必须质检合格才能流转',
    description     VARCHAR(500)           DEFAULT NULL COMMENT '作业指导',
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_route_step_no (route_id, step_no) COMMENT '同一路线工序号唯一',
    KEY idx_route_step (route_id, step_no) COMMENT '按工艺路线查有序工序，高频操作'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工序步骤表（工艺路线明细）';
