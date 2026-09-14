-- =====================================================================
-- mall-content 的库与表（P2 决策 2：内容域现在就拆 schema）
--
-- 执行： mysql -uroot -p < 01-mall_content-schema.sql
-- 幂等： CREATE DATABASE/TABLE IF NOT EXISTS，可重复执行
--
-- 说明：表结构**逐字复制**自单体时代的 mall.cms_banner / mall.cms_notice
--   （含默认值、索引名、字符集、注释），不做任何"顺手优化"——
--   P2 的目标是搬走所有权，不是改表；结构差异会让"回退=改网关路由"这句话不成立。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS `mall_content`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mall_content`.`cms_banner` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(64) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '标题',
  `image_url` varchar(500) COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '图片URL(默认占位图)',
  `link_url` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转链接(如 /product/100)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='轮播图表';

CREATE TABLE IF NOT EXISTS `mall_content`.`cms_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `content` varchar(2000) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '公告内容',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`,`sort`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='公告表';
