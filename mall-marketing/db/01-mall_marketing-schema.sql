-- =====================================================================
-- mall-marketing 的库与表（P5 批次 1：把营销域（优惠券）的所有权搬出来）
--
-- 生成方式：两张表的 DDL 由**现网表直接导出后改写**，不手抄（手抄必然结构漂移）：
--   D:\MySQL\MySQL\bin\mysqldump.exe -uroot -p123456 --no-data --skip-comments \
--       --skip-add-locks --no-tablespaces mall sms_coupon sms_coupon_member
--
-- 相对导出的三处**刻意**改写（其余逐字一致：列类型/默认值/COMMENT/索引/AUTO_INCREMENT）：
--   ① 库名：`mall` → `mall_marketing`（表名保持 `sms_coupon` / `sms_coupon_member` **不变**——
--      将来删旧表（db/03）才是对称的，也便于 grep "谁还在读写 sms_coupon*" 时两边同名可比对）；
--   ② 每张表前补 `DROP TABLE IF EXISTS`（导出里本来就有，保留）：本脚本是"幂等重建"，
--      但**只在表还没有营销域自己的写入时**才安全——批次 4 之后本脚本不得再执行（见 02 的说明）；
--   ③ `sms_coupon_member.coupon_status` 的列注释补上新的第 4 个取值 `3锁定中`
--      （P5 三态新增，见《微服务改造方案.md》§4.3 / .dsh-notes/P5-remaining-plan.md 的 C1 约束）。
--      **只是注释**，值域变化不影响任何列定义、索引与驱动行为。
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 01-mall_marketing-schema.sql
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `mall_marketing`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- sms_coupon（优惠券模板表）—— 逐字导出自 mall.sms_coupon，无结构改动
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `mall_marketing`.`sms_coupon`;
CREATE TABLE IF NOT EXISTS `mall_marketing`.`sms_coupon` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(64) COLLATE utf8mb4_general_ci NOT NULL COMMENT '券名称',
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
) ENGINE=InnoDB AUTO_INCREMENT=115 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='优惠券模板表';

-- ---------------------------------------------------------------------
-- sms_coupon_member（用户优惠券表）—— 逐字导出自 mall.sms_coupon_member，
-- 唯一改写是 coupon_status 的**列注释**（新增取值 3=锁定中，见文件头改动 ③）。
--
-- 【三态取值与对外词表的分离（C1 硬约束，方案 §4.3.1 ②③）】
--   库里：0 未使用 / 1 已使用 / 2 已过期 / **3 锁定中（P5 新增）**
--   对外：3 必须**投影成 1**（前端 CouponCenter.vue / CouponList.vue 已经把 0/1/2 钉成
--         "未使用/已使用/已过期"词表，锁定中的券对外必须仍显示"已使用"——
--         旧实现里下单瞬间券就已 USED，锁定窗口内对外表现不能变）。
--         投影实现在 com.mall.marketing.support.CouponStatusProjection，不在 SQL 里。
--
-- 【索引】导出原样保留：
--   · uk_member_template(member_id, template_id)：同一会员对同一模板只能领一张
--     （P5 的 lock/use/unlock 都按主键 id 走，不依赖它）；
--   · idx_member_status(member_id, coupon_status)："我的券 + 状态过滤"的支撑索引——
--     对外 `?status=1` 要筛 IN (1,3)，该复合索引的等值前缀仍然用得上；
--   · idx_template_id(template_id)：按模板查发放记录。
--   P5 批次 1 **不新增索引**：三态的 CAS 全部 WHERE id = ?（主键），
--   "扫 LOCKED 券对账"需要的索引属于批次 5（每日对账），到那时按真实 SQL 再定。
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `mall_marketing`.`sms_coupon_member`;
CREATE TABLE IF NOT EXISTS `mall_marketing`.`sms_coupon_member` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_id` bigint NOT NULL COMMENT '券模板ID',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `coupon_status` tinyint NOT NULL DEFAULT '0' COMMENT '券状态 0未使用 1已使用 2已过期 3锁定中(P5三态新增,对外投影为1)',
  `receive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
  `expire_time` datetime NOT NULL COMMENT '单券到期时间(领取时按模板计算)',
  `order_no` varchar(32) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT '使用订单号(下单锁定回填,unlock时清空)',
  `use_time` datetime DEFAULT NULL COMMENT '使用时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_template` (`member_id`,`template_id`),
  KEY `idx_member_status` (`member_id`,`coupon_status`),
  KEY `idx_template_id` (`template_id`)
) ENGINE=InnoDB AUTO_INCREMENT=868 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户优惠券表';
