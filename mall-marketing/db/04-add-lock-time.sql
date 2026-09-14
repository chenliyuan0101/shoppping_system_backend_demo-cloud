-- =====================================================================
-- P5 步骤 E：给 sms_coupon_member 加 lock_time 列
--
-- 为什么需要这一列（方案 §4.3 / P5-stepE-plan.md 第二节）：
--   `unlock` 依赖"关单路径被走到"（会员取消 / 超时关单 / 后台关单 / 下单事务回滚补偿），
--   而 MQ 丢事件、服务重启、人工改库都可能让某次关单**没触发解锁** —— 那这张券就永远停在
--   LOCKED(3)：对外投影成"已使用"，用户既用不了也看不见（旧实现是直接烧成 USED，问题更隐蔽）。
--   有了 lock_time，每日对账才能问出"这张券被锁多久了"，超过阈值就解锁 + WARN，作为最后一道防线。
--
-- 生命周期：lock 时写、unlock 时清空；use（核销）**不清** —— 它是审计信息。
-- 历史行：加列后已有 LOCKED 行的 lock_time 为 NULL，对账查询刻意把 `lock_time IS NULL` 也算作
--        "锁太久"（否则这些行永远收拾不到）。
--
-- 幂等：可重复执行（先查 information_schema 再决定是否 ALTER），
--       因为开发/CI 环境可能已经执行过。
-- 表在 `mall_marketing` 库（券的属主），不在 `mall`。
-- =====================================================================

SET @has_col := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'sms_coupon_member'
     AND COLUMN_NAME = 'lock_time'
);

SET @ddl := IF(@has_col = 0,
  'ALTER TABLE sms_coupon_member ADD COLUMN lock_time DATETIME NULL COMMENT ''锁定时刻(下单占用);解锁时清空,核销后保留作审计'' AFTER order_no',
  'SELECT ''lock_time 已存在，跳过'' AS skipped');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 自检：打印列定义与当前锁定行的分布（应为 0 行，除非真的有人在锁定中）
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sms_coupon_member' AND COLUMN_NAME = 'lock_time';

SELECT coupon_status, COUNT(*) AS rows_cnt, SUM(lock_time IS NOT NULL) AS with_lock_time
  FROM sms_coupon_member GROUP BY coupon_status ORDER BY coupon_status;
