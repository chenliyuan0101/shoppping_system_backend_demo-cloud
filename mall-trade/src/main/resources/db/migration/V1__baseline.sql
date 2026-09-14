-- =====================================================================
-- P8-4 Flyway 基线 V1：mall_trade 的**现有结构**（P8-1 建库 + P8-2 切换后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table \
--              --skip-comments --set-gtid-purged=OFF mall_trade
-- ⚠️ 已有库首次接入 Flyway 用 aseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表，V1 **不会被执行**，只在 flyway_schema_history 里记一条基线。
--    空库（全新环境）则 V1 会真的执行，把结构建出来。
-- ⚠️ 与本目录外的 db/01-mall_trade-schema.sql 的关系：**权威 = 本文件**；
--    db/ 下那三份是 P8-1/P8-3 的历史手工脚本，只作留档（别再手跑，否则结构会有两个来源）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号(业务唯一)',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `order_status` tinyint NOT NULL DEFAULT '0' COMMENT '订单状态 0待支付 1待发货 2待收货 3已完成 4已取消 5已关闭 6退款中 7已退款',
  `pay_status` tinyint NOT NULL DEFAULT '0' COMMENT '支付状态 0未支付 1已支付 2已全额退款',
  `pay_channel` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道(一期固定MOCK,预留扩展)',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `source` tinyint NOT NULL DEFAULT '1' COMMENT '订单来源 1购物车结算 2立即购买',
  `total_amount` bigint NOT NULL COMMENT '商品总额(分)',
  `freight_amount` bigint NOT NULL DEFAULT '0' COMMENT '运费(分)',
  `discount_amount` bigint NOT NULL DEFAULT '0' COMMENT '优惠合计(分)(优惠券等)',
  `pay_amount` bigint NOT NULL COMMENT '实付金额(分)=总额-优惠+运费(下单时快照)',
  `coupon_id` bigint DEFAULT NULL COMMENT '使用的优惠券用户券ID(可空)',
  `user_remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '用户备注',
  `receiver_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收货人姓名(地址快照)',
  `receiver_phone` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '收货人电话(快照)',
  `receiver_full_address` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '完整收货地址(快照)',
  `pay_expire_time` datetime DEFAULT NULL COMMENT '支付截止时间(超时自动取消)',
  `logistics_company` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物流公司(发货后)',
  `logistics_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '物流单号(发货后)',
  `ship_time` datetime DEFAULT NULL COMMENT '发货时间',
  `finish_time` datetime DEFAULT NULL COMMENT '完成时间(确认收货)',
  `cancel_time` datetime DEFAULT NULL COMMENT '取消时间(用户/超时)',
  `close_time` datetime DEFAULT NULL COMMENT '关闭时间(后台)',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是(用户删除订单)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_member_status` (`member_id`,`order_status`,`create_time`),
  KEY `idx_expire_scan` (`order_status`,`pay_expire_time`) COMMENT '超时关单扫描索引',
  KEY `idx_create_time` (`create_time`),
  KEY `idx_pay_time` (`pay_time`)
) ENGINE=InnoDB AUTO_INCREMENT=8348 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单主表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_order_daily_stat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `stat_date` date NOT NULL COMMENT '统计日期',
  `order_count` int NOT NULL DEFAULT '0' COMMENT '下单数(按订单创建时间)',
  `paid_count` int NOT NULL DEFAULT '0' COMMENT '支付笔数(按支付时间)',
  `paid_amount` bigint NOT NULL DEFAULT '0' COMMENT '支付金额(分)',
  `refund_count` int NOT NULL DEFAULT '0' COMMENT '退款完成笔数(按售后完成时间)',
  `refund_amount` bigint NOT NULL DEFAULT '0' COMMENT '退款金额(分)',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB AUTO_INCREMENT=2549 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单按日统计表(派生数据,可重算)';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint NOT NULL COMMENT 'SKU ID',
  `spu_title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '商品标题(快照)',
  `sku_name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '规格文本,如"黑色/256G"(快照)',
  `sku_image` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SKU图(快照)',
  `price` bigint NOT NULL COMMENT '成交单价(分,快照)',
  `quantity` int NOT NULL COMMENT '数量',
  `total_amount` bigint NOT NULL COMMENT '小计(分)=单价*数量(快照)',
  `comment_status` tinyint NOT NULL DEFAULT '0' COMMENT '评价状态 0未评价 1已评价',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_sku_id` (`sku_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9013458 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `pay_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '支付流水号(业务唯一)',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `amount` bigint NOT NULL COMMENT '支付金额(分)',
  `channel` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道',
  `pay_status` tinyint NOT NULL COMMENT '流水状态 0失败(模拟失败) 1成功 2已全额退回',
  `pay_time` datetime DEFAULT NULL COMMENT '支付成功时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4883 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='支付流水表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `oms_refund` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `refund_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '售后单号(业务唯一)',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号',
  `order_item_id` bigint DEFAULT NULL COMMENT '订单明细ID(预留按商品售后)',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `refund_type` tinyint NOT NULL COMMENT '类型 1仅退款 2退货退款',
  `reason` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '申请原因',
  `description` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '问题描述',
  `images` json DEFAULT NULL COMMENT '凭证图片数组 JSON',
  `refund_amount` bigint NOT NULL COMMENT '退款金额(分,<=订单实付)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0待处理 1已同意处理中 2已完成(模拟退款成功) 3已拒绝 4用户已撤销',
  `return_company` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退货物流公司(退货退款)',
  `return_tracking_no` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '退货物流单号(退货退款)',
  `received_time` datetime DEFAULT NULL COMMENT '卖家收货确认时间(退货退款)',
  `audit_by` bigint DEFAULT NULL COMMENT '审核人ID(认证域管理员账号表，属 mall-admin)',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '拒绝原因/审核备注',
  `finish_time` datetime DEFAULT NULL COMMENT '完成时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`refund_no`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_member_id` (`member_id`),
  KEY `idx_status` (`status`),
  KEY `idx_finish_time` (`finish_time`)
) ENGINE=InnoDB AUTO_INCREMENT=608 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='售后单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `trade_outbox` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键（也用作投递顺序：按 id 升序发）',
  `event_type` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '事件类型（如 order.paid / order.closed / order.finished）',
  `routing_key` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT 'MQ 路由键（与消费方声明的拓扑逐字一致）',
  `payload` mediumtext COLLATE utf8mb4_general_ci NOT NULL COMMENT '消息正文（JSON，与现状逐字一致——消费方按它反序列化）',
  `biz_key` varchar(64) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '业务键（订单号等），便于排查与"同键去重"',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '0=待发送 1=已发送 2=已放弃(进 DLQ 或人工)',
  `retry_count` int NOT NULL DEFAULT '0' COMMENT '已重试次数',
  `next_retry_at` datetime DEFAULT NULL COMMENT '下次可重投时间（退避；NULL=立即可发）',
  `last_error` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '最后一次失败原因（运维可见；不含敏感信息）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入箱时间（=业务事务提交时间）',
  `sent_at` datetime DEFAULT NULL COMMENT '投递成功时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_next` (`status`,`next_retry_at`) COMMENT '定时重投扫描用：只扫待发送且到期的',
  KEY `idx_biz_key` (`biz_key`),
  KEY `idx_created` (`created_at`) COMMENT '悬挂对账用：找"入箱很久仍未发"的'
) ENGINE=InnoDB AUTO_INCREMENT=202 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交易域事件发件箱（至少一次投递）';
/*!40101 SET character_set_client = @saved_cs_client */;