-- =====================================================================
-- mall_content 的 Flyway 基线 V1：**现有结构**（P2 建库 + 02-migrate-data 之后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table `
--              --skip-comments --set-gtid-purged=OFF mall_content
-- ⚠️ 已有库首次接入 Flyway 用 baseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表 ⇒ V1 **不会被执行**，只在 flyway_schema_history 里记一条基线；
--    空库（全新环境）⇒ V1 会真的执行，把结构建出来（同一份脚本两种用法都对）。
-- ⚠️ 与本目录外的 db/0x-*.sql 的关系：**权威 = 本文件**。
--    db/ 下那几份是建库/迁数据时期的历史手工脚本，只作留档：别再手跑，否则结构会有两个来源。
-- ⚠️ **之后的任何结构变更一律新增 V2__xxx.sql，不要修改本文件**（改了会让已接入的库校验失败）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_banner` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '标题',
  `image_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '图片URL(默认占位图)',
  `link_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '跳转链接(如 /product/100)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_sort` (`status`,`sort`)
) ENGINE=InnoDB AUTO_INCREMENT=197 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='轮播图表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cms_notice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `title` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '公告标题',
  `content` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '公告内容',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `publish_time` datetime DEFAULT NULL COMMENT '发布时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`,`sort`)
) ENGINE=InnoDB AUTO_INCREMENT=109 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
