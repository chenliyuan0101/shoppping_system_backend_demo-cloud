-- =====================================================================
-- mall_marketing 的 Flyway 基线 V1：**现有结构**（P5 建库 + 04-add-lock-time 之后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table `
--              --skip-comments --set-gtid-purged=OFF mall_marketing
-- ⚠️ 已有库首次接入 Flyway 用 baseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表 ⇒ V1 **不会被执行**，只在 flyway_schema_history 里记一条基线；
--    空库（全新环境）⇒ V1 会真的执行，把结构建出来（同一份脚本两种用法都对）。
-- ⚠️ 与本目录外的 db/0x-*.sql 的关系：**权威 = 本文件**。
--    db/ 下那几份是建库/迁数据时期的历史手工脚本，只作留档：别再手跑，否则结构会有两个来源。
-- ⚠️ **之后的任何结构变更一律新增 V2__xxx.sql，不要修改本文件**（改了会让已接入的库校验失败）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '券名称',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '券类型 1满减券(直减)',
  `threshold_amount` bigint NOT NULL DEFAULT '0' COMMENT '满减门槛(分,0=无门槛)',
  `discount_amount` bigint NOT NULL COMMENT '减免金额(分)',
  `total_count` int DEFAULT NULL COMMENT '发行总量(NULL=不限量)',
  `received_count` int NOT NULL DEFAULT '0' COMMENT '已领取数',
  `per_member_limit` int NOT NULL DEFAULT '1' COMMENT '每人限领数',
  `valid_type` tinyint NOT NULL DEFAULT '1' COMMENT '有效期类型 1固定时间段 2领取后N天有效',
  `valid_start_time` datetime DEFAULT NULL COMMENT '生效开始(固定时间段)',
  `valid_end_time` datetime DEFAULT NULL COMMENT '生效结束(固定时间段)',
  `valid_days` int DEFAULT NULL COMMENT '领取后有效天数(valid_type=2)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0启用 1停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=9100000000056 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='优惠券模板表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sms_coupon_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_id` bigint NOT NULL COMMENT '券模板ID',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `coupon_status` tinyint NOT NULL DEFAULT '0' COMMENT '券状态 0未使用 1已使用 2已过期 3锁定中(P5三态新增,对外投影为1)',
  `receive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  `expire_time` datetime NOT NULL COMMENT '单券到期时间(领取时按模板计算)',
  `order_no` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '使用订单号(下单锁定回填,unlock时清空)',
  `lock_time` datetime DEFAULT NULL COMMENT '锁定时刻(下单占用);解锁时清空,核销后保留作审计',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_template` (`member_id`,`template_id`),
  KEY `idx_member_status` (`member_id`,`coupon_status`),
  KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9200000000108 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户优惠券表';
/*!40101 SET character_set_client = @saved_cs_client */;
