-- =====================================================================
-- 01-mall_product-schema.sql —— 建库 + 建表（6 张白名单表，幂等）
--
-- ⚠️ 本文件的 CREATE TABLE 段**不是手抄的**：由
--     mysqldump -uroot --no-data --skip-add-drop-table --skip-comments --set-gtid-purged=OFF \
--       --default-character-set=utf8mb4 mall pms_spu pms_spu_detail pms_sku \
--       pms_sku_stock_log pms_category pms_brand
--   导出后只做三处改写（手抄 DDL 必然结构漂移，这正是 P6-1 规格 §2 明令禁止的）：
--     ① CREATE TABLE `x` → CREATE TABLE IF NOT EXISTS `x`（脚本可重复执行）
--     ② 只保留 6 段 CREATE TABLE 本体，去掉导出文件的所有会话前置语句
--        （SET @OLD_* / GTID / SQL_NOTES / 每段前后的 SET character_set_client 这类
--         `/*!...*/;` 行）——⚠️ 实测踩过：只删开头那几行而留下表末的
--         `SET character_set_client = @saved_cs_client`，会以
--         "Variable 'character_set_client' can't be set to the value of 'NULL'" 直接失败；
--     ③ 补 CREATE DATABASE IF NOT EXISTS + USE（导出文件本身不带库）
--   实测（查过导出、不是假设）：这 6 张表**没有** DEFINER（DEFINER 只出现在视图/触发器/
--   存储过程，本次 0 处），字符集/排序规则已是 utf8mb4 / utf8mb4_general_ci（**没有** utf8mb3 混用），
--   因此"去 DEFINER / 统一 utf8mb4"这两项改写在本库上是**空操作**——写在这里是为了让下一个人
--   知道它们被检查过、而不是被跳过。
--
-- ⚠️ `AUTO_INCREMENT=<原值>` 段**刻意保留**（如 pms_spu=22031）：
--   迁移是"带主键显式插入"，若把自增值重置为 1，P6-4 切换后后台新建商品的 id
--   会撞上已存在的行（Duplicate entry）——这是保留它的唯一理由。
--
-- ⚠️ 表清单是**白名单 6 张**，不是 LIKE 'pms%'：
--   mall.pms_comment（2502 行）属**评价域**（P4 已搬去 mall_review），
--   这一份是 P8 前的回滚副本，**不属于商品域、不得迁入**。
--
-- 执行：
--   $env:MYSQL_PWD='123456'; D:\MySQL\MySQL\bin\mysql.exe -uroot < db/01-mall_product-schema.sql
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `mall_product`
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

USE `mall_product`;


CREATE TABLE IF NOT EXISTS `pms_spu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category_id` bigint NOT NULL COMMENT '类目ID',
  `brand_id` bigint DEFAULT NULL COMMENT '品牌ID',
  `title` varchar(200) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品标题',
  `subtitle` varchar(200) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '副标题/卖点',
  `main_image` varchar(500) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '主图URL(默认占位图)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '上下架 0下架 1上架',
  `recommended` tinyint NOT NULL DEFAULT '0' COMMENT '首页推荐 0否 1是',
  `sales` int NOT NULL DEFAULT '0' COMMENT '累计销量(展示口径,由订单确认收货累加)',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_category_id` (`category_id`,`status`),
  KEY `idx_title` (`title`),
  KEY `idx_status` (`status`),
  KEY `idx_status_sales` (`status`,`sales`),
  KEY `idx_status_newest` (`status`,`create_time`),
  KEY `idx_status_update` (`status`,`update_time`)
) ENGINE=InnoDB AUTO_INCREMENT=22034 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品SPU表';

CREATE TABLE IF NOT EXISTS `pms_spu_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `description` varchar(500) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '一句话卖点',
  `images` json DEFAULT NULL COMMENT '图集URL数组 JSON',
  `params` json DEFAULT NULL COMMENT '商品参数 [{name,value}] JSON',
  `detail_html` mediumtext COLLATE utf8mb4_general_ci COMMENT '图文详情富文本HTML',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_id` (`spu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5043 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SPU详情表';

CREATE TABLE IF NOT EXISTS `pms_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_code` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '商家编码',
  `spec_values` json DEFAULT NULL COMMENT '规格值 [{name,value}] JSON',
  `image` varchar(500) COLLATE utf8mb4_general_ci DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT 'SKU图片(覆盖主图，默认占位图)',
  `price` bigint NOT NULL COMMENT '售价(分)',
  `original_price` bigint DEFAULT NULL COMMENT '划线价(分)',
  `stock` int NOT NULL DEFAULT '0' COMMENT '可售库存: 下单即扣减(预占), 取消/超时/退款回补, 后台调整',
  `sales` int NOT NULL DEFAULT '0' COMMENT '销量',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_spu_id` (`spu_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=33184 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品SKU表';

CREATE TABLE IF NOT EXISTS `pms_sku_stock_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `order_no` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '关联订单号(下单/取消/退款时)',
  `change_type` tinyint NOT NULL COMMENT '变动类型 1下单扣减 2用户取消回补 3超时取消回补 4退款回补 5手动调整',
  `delta` int NOT NULL COMMENT '变动数量(正=增加 负=扣减)',
  `before_stock` int NOT NULL COMMENT '变动前库存',
  `after_stock` int NOT NULL COMMENT '变动后库存',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID(手动调整=sys_user, 系统=null)',
  `remark` varchar(255) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_sku_id` (`sku_id`,`create_time`),
  KEY `idx_order_no` (`order_no`),
  -- P6-5：`release` 的**幂等兜底**——同一 (order_no, change_type, sku_id) 只允许一条回补流水。
  --   主判据在应用层（`StockCommandServiceImpl#alreadyLogged`：同事务内 FOR UPDATE 查后跳过），
  --   这条唯一索引是最后一道防线：万一并发/代码路径绕过判据，重复插入会**报错**而不是静默多回补一次。
  --   ⚠️ order_no 可空，而 MySQL 的唯一索引把 NULL 视为互不相同 ⇒ 手工调整（无订单号）的行不会互相冲突 ✓
  UNIQUE KEY `uk_order_type_sku` (`order_no`,`change_type`,`sku_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5776 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='库存变动流水表';

CREATE TABLE IF NOT EXISTS `pms_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父类目ID,0=顶级',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '类目名称',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1114 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品类目表';

CREATE TABLE IF NOT EXISTS `pms_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '品牌名称',
  `logo` varchar(500) COLLATE utf8mb4_general_ci DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '品牌Logo(默认占位图)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1169 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='品牌表';

