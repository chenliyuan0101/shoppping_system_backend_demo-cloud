-- =====================================================================
-- mall_review 的 Flyway 基线 V1：**现有结构**（P4 建库 + 04-backfill-pending 之后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table `
--              --skip-comments --set-gtid-purged=OFF mall_review
-- ⚠️ 已有库首次接入 Flyway 用 baseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表 ⇒ V1 **不会被执行**，只在 flyway_schema_history 里记一条基线；
--    空库（全新环境）⇒ V1 会真的执行，把结构建出来（同一份脚本两种用法都对）。
-- ⚠️ 与本目录外的 db/0x-*.sql 的关系：**权威 = 本文件**。
--    db/ 下那几份是建库/迁数据时期的历史手工脚本，只作留档：别再手跑，否则结构会有两个来源。
-- ⚠️ **之后的任何结构变更一律新增 V2__xxx.sql，不要修改本文件**（改了会让已接入的库校验失败）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `member_nickname` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '会员昵称快照(写入时落库,不随改名变化,P4 新增)',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号(评价来源)',
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID(一单一评,关联粒度)',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID(可选)',
  `rating` tinyint NOT NULL DEFAULT '5' COMMENT '评分 1-5',
  `content` varchar(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '评价内容',
  `images` json DEFAULT NULL COMMENT '晒图URL数组 JSON',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0待审核 1展示 2隐藏',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_spu_status` (`spu_id`,`status`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2830 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品评价表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `review_pending_item` (
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID(主键:事件幂等的天然去重键 + 抢闸门的判定列)',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号',
  `member_id` bigint NOT NULL COMMENT '会员ID(评价归属校验用)',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID(可选)',
  `spu_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '商品标题快照',
  `sku_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SKU 图片快照',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '购买数量',
  `finished_time` datetime NOT NULL COMMENT '订单确认收货时间(算"剩余可评价天数"的基准)',
  `commented` tinyint NOT NULL DEFAULT '0' COMMENT '是否已评价 0否 1是(抢闸门的条件列)',
  `comment_id` bigint DEFAULT NULL COMMENT '抢闸门成功后写入的评价ID(对账用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`order_item_id`),
  KEY `idx_member_commented` (`member_id`,`commented`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='待评价读模型(order.finished 事件的投影)';
/*!40101 SET character_set_client = @saved_cs_client */;
