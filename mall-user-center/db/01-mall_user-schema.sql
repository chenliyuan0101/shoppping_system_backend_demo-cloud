-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_user 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- mall-user-center 的库与表（P3：用户中心拆库）
--
-- 生成方式：由源库 DDL 直接导出后改写，不做任何"顺手优化"——
--   P3 的目标是搬走所有权，不是改表；结构差异会让"回退=改网关路由"这句话不成立。
-- 执行： mysql -uroot -p < 01-mall_user-schema.sql       （幂等，可重复执行）
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `mall_user`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录用户名',
  `password` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码(BCrypt)',
  `nickname` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '昵称',
  `phone` varchar(20) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '头像URL',
  `gender` tinyint NOT NULL DEFAULT '0' COMMENT '性别 0未知 1男 2女',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0禁用 1正常',
  `last_login_time` datetime DEFAULT NULL COMMENT '最后登录时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='会员表';
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_address` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `receiver_name` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '收货人姓名',
  `receiver_phone` varchar(20) COLLATE utf8mb4_general_ci NOT NULL COMMENT '收货人电话',
  `province_code` varchar(10) COLLATE utf8mb4_general_ci NOT NULL COMMENT '省代码(前端省市区数据)',
  `province_name` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '省名称(冗余)',
  `city_code` varchar(10) COLLATE utf8mb4_general_ci NOT NULL COMMENT '市代码(前端省市区数据)',
  `city_name` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '市名称(冗余)',
  `district_code` varchar(10) COLLATE utf8mb4_general_ci NOT NULL COMMENT '区代码(前端省市区数据)',
  `district_name` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '区名称(冗余)',
  `detail` varchar(255) COLLATE utf8mb4_general_ci NOT NULL COMMENT '详细地址',
  `is_default` tinyint NOT NULL DEFAULT '0' COMMENT '是否默认 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收货地址表';
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_cart_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID(冗余,列表展示)',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '数量',
  `checked` tinyint NOT NULL DEFAULT '1' COMMENT '是否勾选 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_sku` (`member_id`,`sku_id`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='购物车表';
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_spu` (`member_id`,`spu_id`),
  KEY `idx_member_id` (`member_id`),
  KEY `idx_member_time` (`member_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收藏表';
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_footprint` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `last_view_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近浏览时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_spu` (`member_id`,`spu_id`),
  KEY `idx_member_id` (`member_id`),
  KEY `idx_member_view` (`member_id`,`last_view_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='浏览足迹表';
CREATE TABLE IF NOT EXISTS `mall_user`.`ums_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `type` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '类型 ORDER_PAID/ORDER_SHIPPED/REFUND_SETTLED',
  `title` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '标题',
  `content` varchar(500) COLLATE utf8mb4_general_ci NOT NULL COMMENT '内容',
  `biz_no` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '业务单号(订单号/售后单号)',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读 0未读 1已读',
  `read_time` datetime DEFAULT NULL COMMENT '读取时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_type_biz` (`member_id`,`type`,`biz_no`),
  KEY `idx_member_read` (`member_id`,`is_read`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='站内消息表(事件驱动,唯一键幂等去重)';
