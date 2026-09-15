-- =====================================================================
-- mall_product 的 Flyway 基线 V1：**现有结构**（P6 建库 + 04-uk-order-type-sku 之后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table `
--              --skip-comments --set-gtid-purged=OFF mall_product
-- ⚠️ 已有库首次接入 Flyway 用 baseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表 ⇒ V1 **不会被执行**，只在 flyway_schema_history 里记一条基线；
--    空库（全新环境）⇒ V1 会真的执行，把结构建出来（同一份脚本两种用法都对）。
-- ⚠️ 与本目录外的 db/0x-*.sql 的关系：**权威 = 本文件**。
--    db/ 下那几份是建库/迁数据时期的历史手工脚本，只作留档：别再手跑，否则结构会有两个来源。
-- ⚠️ **之后的任何结构变更一律新增 V2__xxx.sql，不要修改本文件**（改了会让已接入的库校验失败）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_brand` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '品牌名称',
  `logo` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '品牌Logo(默认占位图)',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=1263 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='品牌表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父类目ID,0=顶级',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '类目名称',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序(小在前)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1251 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品类目表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商家编码',
  `spec_values` json DEFAULT NULL COMMENT '规格值 [{name,value}] JSON',
  `image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT 'SKU图片(覆盖主图，默认占位图)',
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
) ENGINE=InnoDB AUTO_INCREMENT=33505 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品SKU表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_sku_stock_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '关联订单号(下单/取消/退款时)',
  `change_type` tinyint NOT NULL COMMENT '变动类型 1下单扣减 2用户取消回补 3超时取消回补 4退款回补 5手动调整',
  `delta` int NOT NULL COMMENT '变动数量(正=增加 负=扣减)',
  `before_stock` int NOT NULL COMMENT '变动前库存',
  `after_stock` int NOT NULL COMMENT '变动后库存',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID(手动调整=sys_user, 系统=null)',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_type_sku` (`order_no`,`change_type`,`sku_id`),
  KEY `idx_sku_id` (`sku_id`,`create_time`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB AUTO_INCREMENT=8655 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='库存变动流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_spu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category_id` bigint NOT NULL COMMENT '类目ID',
  `brand_id` bigint DEFAULT NULL COMMENT '品牌ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品标题',
  `subtitle` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '副标题/卖点',
  `main_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'http://localhost:9000/mall/seed/placeholder.png' COMMENT '主图URL(默认占位图)',
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
) ENGINE=InnoDB AUTO_INCREMENT=22298 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品SPU表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pms_spu_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '一句话卖点',
  `images` json DEFAULT NULL COMMENT '图集URL数组 JSON',
  `params` json DEFAULT NULL COMMENT '商品参数 [{name,value}] JSON',
  `detail_html` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '图文详情富文本HTML',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spu_id` (`spu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5283 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='SPU详情表';
/*!40101 SET character_set_client = @saved_cs_client */;
