-- =====================================================================
-- 00 创建数据库与公共函数
-- 执行顺序：00 → 01 → 02 → 03 → 04 → 05
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP DATABASE IF EXISTS smart_system;
DROP DATABASE IF EXISTS smart_product;
DROP DATABASE IF EXISTS smart_production;
DROP DATABASE IF EXISTS smart_inventory;
DROP DATABASE IF EXISTS smart_quality;

CREATE DATABASE smart_system      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE smart_product     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE smart_production  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE smart_inventory   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE DATABASE smart_quality     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

SET FOREIGN_KEY_CHECKS = 1;
