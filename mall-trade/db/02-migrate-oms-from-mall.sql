-- ⚠️ P8-4 起：**权威结构脚本已迁到 Flyway** —— `src/main/resources/db/migration/V1__baseline.sql`
--    本文件降级为**历史手工脚本**（P8-1/P8-3 期的一次性产物，留档用）。
--    **不要再手跑它**：否则"结构"会有两个来源，Flyway 的 `flyway_schema_history` 与实际库不一致。
--    （应用启动时 Flyway 会自动 migrate/baseline，不需要人工执行任何 SQL。）
-- =====================================================================
-- P8-1：把 5 张交易域表的数据从 `mall` 迁到 `mall_trade`（**幂等、可重跑**）
-- 写法与 mall-product/db/02 同口径：**按主键 UPSERT**（重跑 0 行变更），不用 REPLACE（避免删+插的副作用）。
-- ⚠️ 两库在**同一实例**上 ⇒ 用跨库 `INSERT ... SELECT`，不需要导出/导入数据文件。
-- ⚠️ `mall` 在 P8-2 切换前仍是权威；本脚本可反复重跑，切换前再跑一次即可对齐。
-- 生成时间：2026-09-14 11:40:01
-- =====================================================================
USE mall_trade;

-- ---- oms_order（27 列，主键 id）----
INSERT INTO mall_trade.oms_order (id, order_no, member_id, order_status, pay_status, pay_channel, pay_time, source, total_amount, freight_amount, discount_amount, pay_amount, coupon_id, user_remark, receiver_name, receiver_phone, receiver_full_address, pay_expire_time, logistics_company, logistics_no, ship_time, finish_time, cancel_time, close_time, deleted, create_time, update_time)
SELECT id, order_no, member_id, order_status, pay_status, pay_channel, pay_time, source, total_amount, freight_amount, discount_amount, pay_amount, coupon_id, user_remark, receiver_name, receiver_phone, receiver_full_address, pay_expire_time, logistics_company, logistics_no, ship_time, finish_time, cancel_time, close_time, deleted, create_time, update_time FROM mall.oms_order
ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), member_id = VALUES(member_id), order_status = VALUES(order_status), pay_status = VALUES(pay_status), pay_channel = VALUES(pay_channel), pay_time = VALUES(pay_time), source = VALUES(source), total_amount = VALUES(total_amount), freight_amount = VALUES(freight_amount), discount_amount = VALUES(discount_amount), pay_amount = VALUES(pay_amount), coupon_id = VALUES(coupon_id), user_remark = VALUES(user_remark), receiver_name = VALUES(receiver_name), receiver_phone = VALUES(receiver_phone), receiver_full_address = VALUES(receiver_full_address), pay_expire_time = VALUES(pay_expire_time), logistics_company = VALUES(logistics_company), logistics_no = VALUES(logistics_no), ship_time = VALUES(ship_time), finish_time = VALUES(finish_time), cancel_time = VALUES(cancel_time), close_time = VALUES(close_time), deleted = VALUES(deleted), create_time = VALUES(create_time), update_time = VALUES(update_time);

-- ---- oms_order_item（12 列，主键 id）----
INSERT INTO mall_trade.oms_order_item (id, order_no, spu_id, sku_id, spu_title, sku_name, sku_image, price, quantity, total_amount, comment_status, create_time)
SELECT id, order_no, spu_id, sku_id, spu_title, sku_name, sku_image, price, quantity, total_amount, comment_status, create_time FROM mall.oms_order_item
ON DUPLICATE KEY UPDATE order_no = VALUES(order_no), spu_id = VALUES(spu_id), sku_id = VALUES(sku_id), spu_title = VALUES(spu_title), sku_name = VALUES(sku_name), sku_image = VALUES(sku_image), price = VALUES(price), quantity = VALUES(quantity), total_amount = VALUES(total_amount), comment_status = VALUES(comment_status), create_time = VALUES(create_time);

-- ---- oms_payment（9 列，主键 id）----
INSERT INTO mall_trade.oms_payment (id, pay_no, order_no, member_id, amount, channel, pay_status, pay_time, create_time)
SELECT id, pay_no, order_no, member_id, amount, channel, pay_status, pay_time, create_time FROM mall.oms_payment
ON DUPLICATE KEY UPDATE pay_no = VALUES(pay_no), order_no = VALUES(order_no), member_id = VALUES(member_id), amount = VALUES(amount), channel = VALUES(channel), pay_status = VALUES(pay_status), pay_time = VALUES(pay_time), create_time = VALUES(create_time);

-- ---- oms_refund（20 列，主键 id）----
INSERT INTO mall_trade.oms_refund (id, refund_no, order_no, order_item_id, member_id, refund_type, reason, description, images, refund_amount, status, return_company, return_tracking_no, received_time, audit_by, audit_time, audit_remark, finish_time, create_time, update_time)
SELECT id, refund_no, order_no, order_item_id, member_id, refund_type, reason, description, images, refund_amount, status, return_company, return_tracking_no, received_time, audit_by, audit_time, audit_remark, finish_time, create_time, update_time FROM mall.oms_refund
ON DUPLICATE KEY UPDATE refund_no = VALUES(refund_no), order_no = VALUES(order_no), order_item_id = VALUES(order_item_id), member_id = VALUES(member_id), refund_type = VALUES(refund_type), reason = VALUES(reason), description = VALUES(description), images = VALUES(images), refund_amount = VALUES(refund_amount), status = VALUES(status), return_company = VALUES(return_company), return_tracking_no = VALUES(return_tracking_no), received_time = VALUES(received_time), audit_by = VALUES(audit_by), audit_time = VALUES(audit_time), audit_remark = VALUES(audit_remark), finish_time = VALUES(finish_time), create_time = VALUES(create_time), update_time = VALUES(update_time);

-- ---- oms_order_daily_stat（8 列，主键 id）----
INSERT INTO mall_trade.oms_order_daily_stat (id, stat_date, order_count, paid_count, paid_amount, refund_count, refund_amount, update_time)
SELECT id, stat_date, order_count, paid_count, paid_amount, refund_count, refund_amount, update_time FROM mall.oms_order_daily_stat
ON DUPLICATE KEY UPDATE stat_date = VALUES(stat_date), order_count = VALUES(order_count), paid_count = VALUES(paid_count), paid_amount = VALUES(paid_amount), refund_count = VALUES(refund_count), refund_amount = VALUES(refund_amount), update_time = VALUES(update_time);

-- ---- 自检：双侧 COUNT(*)|MAX(id)|MAX(update_time) 必须逐表相等 ----
SELECT '== 对齐自检（左=mall 权威 / 右=mall_trade 目标）==' AS check_block;
SELECT 'oms_order' AS tbl, (SELECT COUNT(*) FROM mall.oms_order) AS src_rows, (SELECT COUNT(*) FROM mall_trade.oms_order) AS dst_rows, (SELECT IFNULL(MAX(id),0) FROM mall.oms_order) AS src_maxid, (SELECT IFNULL(MAX(id),0) FROM mall_trade.oms_order) AS dst_maxid, IFNULL((SELECT MAX(update_time) FROM mall.oms_order),'-') AS src_ts, IFNULL((SELECT MAX(update_time) FROM mall_trade.oms_order),'-') AS dst_ts;
SELECT 'oms_order_item' AS tbl, (SELECT COUNT(*) FROM mall.oms_order_item) AS src_rows, (SELECT COUNT(*) FROM mall_trade.oms_order_item) AS dst_rows, (SELECT IFNULL(MAX(id),0) FROM mall.oms_order_item) AS src_maxid, (SELECT IFNULL(MAX(id),0) FROM mall_trade.oms_order_item) AS dst_maxid, IFNULL((SELECT MAX(create_time) FROM mall.oms_order_item),'-') AS src_ts, IFNULL((SELECT MAX(create_time) FROM mall_trade.oms_order_item),'-') AS dst_ts;
SELECT 'oms_payment' AS tbl, (SELECT COUNT(*) FROM mall.oms_payment) AS src_rows, (SELECT COUNT(*) FROM mall_trade.oms_payment) AS dst_rows, (SELECT IFNULL(MAX(id),0) FROM mall.oms_payment) AS src_maxid, (SELECT IFNULL(MAX(id),0) FROM mall_trade.oms_payment) AS dst_maxid, IFNULL((SELECT MAX(create_time) FROM mall.oms_payment),'-') AS src_ts, IFNULL((SELECT MAX(create_time) FROM mall_trade.oms_payment),'-') AS dst_ts;
SELECT 'oms_refund' AS tbl, (SELECT COUNT(*) FROM mall.oms_refund) AS src_rows, (SELECT COUNT(*) FROM mall_trade.oms_refund) AS dst_rows, (SELECT IFNULL(MAX(id),0) FROM mall.oms_refund) AS src_maxid, (SELECT IFNULL(MAX(id),0) FROM mall_trade.oms_refund) AS dst_maxid, IFNULL((SELECT MAX(update_time) FROM mall.oms_refund),'-') AS src_ts, IFNULL((SELECT MAX(update_time) FROM mall_trade.oms_refund),'-') AS dst_ts;
SELECT 'oms_order_daily_stat' AS tbl, (SELECT COUNT(*) FROM mall.oms_order_daily_stat) AS src_rows, (SELECT COUNT(*) FROM mall_trade.oms_order_daily_stat) AS dst_rows, (SELECT IFNULL(MAX(id),0) FROM mall.oms_order_daily_stat) AS src_maxid, (SELECT IFNULL(MAX(id),0) FROM mall_trade.oms_order_daily_stat) AS dst_maxid, IFNULL((SELECT MAX(update_time) FROM mall.oms_order_daily_stat),'-') AS src_ts, IFNULL((SELECT MAX(update_time) FROM mall_trade.oms_order_daily_stat),'-') AS dst_ts;
