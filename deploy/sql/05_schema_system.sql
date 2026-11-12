-- =====================================================================
-- 06 · 系统库 smart_system
-- 表：sys_user / sys_role / sys_user_role
-- =====================================================================

USE smart_system;

-- ---------------------------------------------------------------------
-- 用户表。password 存 BCrypt 哈希（不可逆），绝不存明文。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user;
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(64)  NOT NULL COMMENT '登录名，唯一',
    password    VARCHAR(128) NOT NULL COMMENT 'BCrypt 哈希（$2a$10$...），不可逆',
    real_name   VARCHAR(64)           DEFAULT NULL COMMENT '真实姓名',
    phone       VARCHAR(32)           DEFAULT NULL COMMENT '手机号',
    email       VARCHAR(128)          DEFAULT NULL COMMENT '邮箱',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用（停用后禁止登录）',
    remark      VARCHAR(500)          DEFAULT NULL COMMENT '备注',
    create_by   BIGINT                DEFAULT NULL COMMENT '创建人',
    update_by   BIGINT                DEFAULT NULL COMMENT '更新人',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_user_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统用户表';

-- ---------------------------------------------------------------------
-- 角色表。本期角色简单（管理员/计划员/仓库员/工人/质检员），
-- 用独立表而不是 user.role 字段，是为了以后能加"一个用户多角色"。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_role;
CREATE TABLE sys_role (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码 ADMIN/PLANNER/WAREHOUSE/OPERATOR/INSPECTOR',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    description VARCHAR(255)          DEFAULT NULL COMMENT '描述',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-停用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='系统角色表';

-- ---------------------------------------------------------------------
-- 用户角色关联表（多对多）
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS sys_user_role;
CREATE TABLE sys_user_role (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL COMMENT '用户',
    role_id     BIGINT   NOT NULL COMMENT '角色',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_ur_user (user_id),
    KEY idx_ur_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户角色关联表';
