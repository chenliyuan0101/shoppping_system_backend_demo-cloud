-- =====================================================================
-- 数据搬迁：mall.sms_coupon / mall.sms_coupon_member → mall_marketing 同名两表（P5 批次 1）
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 02-migrate-data.sql
--
-- ⚠️ 过渡期（P5 批次 1~3）单体 sms 仍是 mall.sms_coupon* 的**唯一写入方**
--    （下单核销、领券、后台建券都还在单体，见 .dsh-notes/P5-remaining-plan.md 的批次划分）。
--    因此本脚本要在"批次 4 切换前"再执行一次，把期间的新数据补齐。
--
-- 【幂等做法】整表重灌：先 DELETE 再全量 INSERT ... SELECT（与 P4 的 02-migrate-data.sql 同一套）。
--   刻意**不**用 INSERT IGNORE / ON DUPLICATE KEY UPDATE：
--     · 重灌的语义是"新库这两张表 == 源库那两张表"，一条 SELECT 就能对齐行数；
--     · ON DUPLICATE KEY UPDATE 遇到源库删过行的情况会留下源库没有的"幽灵行"，
--       而行数对齐恰恰是本脚本的验收标准。
--   ⚠️ 代价：**批次 4 之后本脚本不得再执行**——那时 marketing 自己是唯一写入方，
--     重灌会把库里真实的锁定/核销状态覆盖回源库的旧值（源库那时已被删表，脚本本身也会报错）。
--     这个前提写在 db/03-drop-from-mall.sql 里，与"删旧表"是同一个时间点。
--
-- 【不搬的东西】sms_coupon.received_count 原样搬（它是"已领取数"的冗余计数，归属营销域）；
--   不重算、不校正——搬迁只搬所有权，不改数据（改数据属批次 4 之后的事）。
--
-- 【新增取值 3 的历史数据】存量 coupon_status 只可能是 0/1/2（旧实现没有锁定期），
--   脚本不做任何状态改写；自检里把 0/1/2/3 的分布一并打出来，作为"存量没有 3"的证据。
-- =====================================================================

USE `mall_marketing`;

SET FOREIGN_KEY_CHECKS = 0;

-- 幂等：整表重灌（先子后父，虽然两表之间没有外键）
DELETE FROM `sms_coupon_member`;
DELETE FROM `sms_coupon`;

-- ① 券模板（逐列显式列出：不写 `SELECT *`，源表将来加列时本脚本会**显式失败**而不是静默错位）
INSERT INTO `sms_coupon`
    (`id`, `name`, `type`, `threshold_amount`, `discount_amount`, `total_count`, `received_count`,
     `per_member_limit`, `valid_type`, `valid_start_time`, `valid_end_time`, `valid_days`,
     `status`, `create_time`, `update_time`)
SELECT `id`, `name`, `type`, `threshold_amount`, `discount_amount`, `total_count`, `received_count`,
       `per_member_limit`, `valid_type`, `valid_start_time`, `valid_end_time`, `valid_days`,
       `status`, `create_time`, `update_time`
FROM `mall`.`sms_coupon`;

-- ② 会员券（id 原样搬：对外 id 不变，且回退时两边 id 可对齐）
INSERT INTO `sms_coupon_member`
    (`id`, `template_id`, `member_id`, `coupon_status`, `receive_time`, `expire_time`,
     `order_no`, `use_time`)
SELECT `id`, `template_id`, `member_id`, `coupon_status`, `receive_time`, `expire_time`,
       `order_no`, `use_time`
FROM `mall`.`sms_coupon_member`;

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 自检（执行后应看到 src/dst 两列**逐表相等**）
-- =====================================================================
SELECT 'sms_coupon' AS tbl,
       (SELECT COUNT(*) FROM `mall`.`sms_coupon`)         AS src_rows,
       (SELECT COUNT(*) FROM `mall_marketing`.`sms_coupon`) AS dst_rows
UNION ALL
SELECT 'sms_coupon_member',
       (SELECT COUNT(*) FROM `mall`.`sms_coupon_member`),
       (SELECT COUNT(*) FROM `mall_marketing`.`sms_coupon_member`);

-- 会员券的"内容对齐"（只对行数相等还不够：错位/串行也会行数相等）
SELECT (SELECT COUNT(*) FROM `mall`.`sms_coupon_member` a
         JOIN `mall_marketing`.`sms_coupon_member` b
           ON b.`id` = a.`id` AND b.`member_id` = a.`member_id`
          AND b.`template_id` = a.`template_id` AND b.`coupon_status` = a.`coupon_status`
          AND b.`order_no` <=> a.`order_no` AND b.`use_time` <=> a.`use_time`
          AND b.`expire_time` = a.`expire_time`) AS src_rows_matched_exactly;

-- 状态分布（照出 3 = 存量里刻意没有的"锁定中"）
SELECT `coupon_status`, COUNT(*) AS rows_cnt
FROM `mall_marketing`.`sms_coupon_member`
GROUP BY `coupon_status`
ORDER BY `coupon_status`;
