-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_product 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- 数据搬迁：mall.pms_*（白名单 6 张）→ mall_product 同名 6 表（P6-1）
--
-- 执行： $env:MYSQL_PWD='123456'; D:\MySQL\bin\mysql.exe -uroot --default-character-set=utf8mb4 < 02-migrate-data.sql
--        （建议加 -v 或 -t，输出的每段自检就能当验收证据直接贴）
--
-- ⚠️ 过渡期（P6-1 ~ P6-3）**单体仍是 mall.pms_* 的唯一写入方**（下单扣库存、后台改商品、
--    超时回补都还在单体）。因此本脚本要在 P6-4 切换前**再执行一次**，把期间的新数据补齐；
--    切到 mall-product 之后（P6-4 起）本脚本**不得再执行**——那时 product 自己是写入方，
--    重跑会把库里的真实库存/销量覆盖回源库的旧值（这个时间点与 db/03 删旧表是同一个）。
--
-- =====================================================================
-- 【幂等做法：按主键 UPSERT，不是 P4/P5 的"先 DELETE 再全量重灌"】
--
-- 规格 §3/§9 明确要求"重跑第二次应影响 0 行"，因此这里用
-- `INSERT ... ON DUPLICATE KEY UPDATE`（逐列赋值），而**不是** P4/P5 的整表重灌。
-- 两点必须说清（这是本脚本与 P5 的 db/02 唯一的实质差异）：
--   · 为什么 P5 用重灌、这里不能照抄：重灌的验收证据是"DELETE 掉 N 行 + INSERT N 行"，
--     **拿不到"第二次影响 0 行"这个证据**；而 P6-1 的验收第 3 条正是它。
--   · 重灌还有一个 P5 明写的优点：源库删过的行不会在新库留下"幽灵行"。
--     这里改用 UPSERT 后**用一条自检查询把这件事补回来**（见文末 check ③：
--     dst 里存在、src 里不存在的行数必须是 0）。P6-1 是一次性复制，源库当前不会被删行，
--     但这条断言必须有——否则"重灌的优点"就只是换了个地方消失。
--
-- MySQL 的 affected-rows 语义（本脚本"第二次 0 行"的技术依据）：
--   新插入 1 行 → 1；命中主键且**值有变化** → 2；命中主键但**值完全相同** → 0。
--   所以"重跑第二次全为 0"成立的前提是：源库在这两次之间没有任何变化（正常情况如此）。
--   ⚠️ 若过渡期源库有增量，重跑会看到 0/1/2 混合——那不是脚本坏了，是它把增量补上了；
--      此时应以文末 check ①（行数对齐）为准，而不是拿"必须全 0"当死判据。
-- =====================================================================
--
-- 【不搬的东西（白名单之外一律不动）】
--   · mall.pms_comment（2502 行）：**评价域**的表（P4 已搬去 mall_review），
--     这一份是 P8 之前的回滚副本，不属于商品域 ⇒ 不迁、不删、不碰；
--   · 其它任何 pms_ 前缀表：本脚本只写上面 6 张表名，不用 LIKE 'pms%'（规格 §2 硬性禁令）。
--
-- 【列清单是显式写的，不用 SELECT *】
--   源表将来加列时本脚本会**直接报错**（列数不匹配）而不是静默错位。
-- =====================================================================

USE `mall_product`;

SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------
-- ① pms_spu（商品 SPU）
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_spu`
    (`id`, `category_id`, `brand_id`, `title`, `subtitle`, `main_image`,
     `status`, `recommended`, `sales`, `deleted`, `create_time`, `update_time`)
SELECT `id`, `category_id`, `brand_id`, `title`, `subtitle`, `main_image`,
       `status`, `recommended`, `sales`, `deleted`, `create_time`, `update_time`
FROM `mall`.`pms_spu`
ON DUPLICATE KEY UPDATE
    `category_id` = VALUES(`category_id`), `brand_id` = VALUES(`brand_id`),
    `title` = VALUES(`title`), `subtitle` = VALUES(`subtitle`),
    `main_image` = VALUES(`main_image`), `status` = VALUES(`status`),
    `recommended` = VALUES(`recommended`), `sales` = VALUES(`sales`),
    `deleted` = VALUES(`deleted`), `create_time` = VALUES(`create_time`),
    `update_time` = VALUES(`update_time`);
SELECT 'pms_spu' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

-- ---------------------------------------------------------------------
-- ② pms_spu_detail（商品详情：富文本/图集/参数）
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_spu_detail`
    (`id`, `spu_id`, `description`, `images`, `params`, `detail_html`, `update_time`)
SELECT `id`, `spu_id`, `description`, `images`, `params`, `detail_html`, `update_time`
FROM `mall`.`pms_spu_detail`
ON DUPLICATE KEY UPDATE
    `spu_id` = VALUES(`spu_id`), `description` = VALUES(`description`),
    `images` = VALUES(`images`), `params` = VALUES(`params`),
    `detail_html` = VALUES(`detail_html`), `update_time` = VALUES(`update_time`);
SELECT 'pms_spu_detail' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

-- ---------------------------------------------------------------------
-- ③ pms_sku（SKU：价格/库存/销量都是**检索与下单的排序/判定字段**）
--     id 原样搬：库存流水与历史订单都按 sku_id 引用，换主键会让回补找不到行。
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_sku`
    (`id`, `spu_id`, `sku_code`, `spec_values`, `image`, `price`, `original_price`,
     `stock`, `sales`, `status`, `deleted`, `create_time`, `update_time`)
SELECT `id`, `spu_id`, `sku_code`, `spec_values`, `image`, `price`, `original_price`,
       `stock`, `sales`, `status`, `deleted`, `create_time`, `update_time`
FROM `mall`.`pms_sku`
ON DUPLICATE KEY UPDATE
    `spu_id` = VALUES(`spu_id`), `sku_code` = VALUES(`sku_code`),
    `spec_values` = VALUES(`spec_values`), `image` = VALUES(`image`),
    `price` = VALUES(`price`), `original_price` = VALUES(`original_price`),
    `stock` = VALUES(`stock`), `sales` = VALUES(`sales`),
    `status` = VALUES(`status`), `deleted` = VALUES(`deleted`),
    `create_time` = VALUES(`create_time`), `update_time` = VALUES(`update_time`);
SELECT 'pms_sku' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

-- ---------------------------------------------------------------------
-- ④ pms_sku_stock_log（库存流水：before/after 是"库存对账"的账本，逐字搬）
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_sku_stock_log`
    (`id`, `sku_id`, `order_no`, `change_type`, `delta`, `before_stock`,
     `after_stock`, `operator_id`, `remark`, `create_time`)
SELECT `id`, `sku_id`, `order_no`, `change_type`, `delta`, `before_stock`,
       `after_stock`, `operator_id`, `remark`, `create_time`
FROM `mall`.`pms_sku_stock_log`
ON DUPLICATE KEY UPDATE
    `sku_id` = VALUES(`sku_id`), `order_no` = VALUES(`order_no`),
    `change_type` = VALUES(`change_type`), `delta` = VALUES(`delta`),
    `before_stock` = VALUES(`before_stock`), `after_stock` = VALUES(`after_stock`),
    `operator_id` = VALUES(`operator_id`), `remark` = VALUES(`remark`),
    `create_time` = VALUES(`create_time`);
SELECT 'pms_sku_stock_log' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

-- ---------------------------------------------------------------------
-- ⑤ pms_category（类目树，两级）
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_category`
    (`id`, `parent_id`, `name`, `sort`, `status`, `create_time`, `update_time`)
SELECT `id`, `parent_id`, `name`, `sort`, `status`, `create_time`, `update_time`
FROM `mall`.`pms_category`
ON DUPLICATE KEY UPDATE
    `parent_id` = VALUES(`parent_id`), `name` = VALUES(`name`),
    `sort` = VALUES(`sort`), `status` = VALUES(`status`),
    `create_time` = VALUES(`create_time`), `update_time` = VALUES(`update_time`);
SELECT 'pms_category' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

-- ---------------------------------------------------------------------
-- ⑥ pms_brand（品牌）
-- ---------------------------------------------------------------------
INSERT INTO `mall_product`.`pms_brand`
    (`id`, `name`, `logo`, `sort`, `status`, `create_time`, `update_time`)
SELECT `id`, `name`, `logo`, `sort`, `status`, `create_time`, `update_time`
FROM `mall`.`pms_brand`
ON DUPLICATE KEY UPDATE
    `name` = VALUES(`name`), `logo` = VALUES(`logo`),
    `sort` = VALUES(`sort`), `status` = VALUES(`status`),
    `create_time` = VALUES(`create_time`), `update_time` = VALUES(`update_time`);
SELECT 'pms_brand' AS migrated_table, ROW_COUNT() AS affected_rows_of_last_stmt;

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 自检
-- =====================================================================
-- check ① 逐表行数对齐（**P6-1 的验收基线**：1508/1508/2438/2441/69/46）
SELECT 'pms_spu' AS tbl,
       (SELECT COUNT(*) FROM `mall`.`pms_spu`) AS src_rows,
       (SELECT COUNT(*) FROM `mall_product`.`pms_spu`) AS dst_rows
UNION ALL SELECT 'pms_spu_detail',
       (SELECT COUNT(*) FROM `mall`.`pms_spu_detail`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_spu_detail`)
UNION ALL SELECT 'pms_sku',
       (SELECT COUNT(*) FROM `mall`.`pms_sku`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_sku`)
UNION ALL SELECT 'pms_sku_stock_log',
       (SELECT COUNT(*) FROM `mall`.`pms_sku_stock_log`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_sku_stock_log`)
UNION ALL SELECT 'pms_category',
       (SELECT COUNT(*) FROM `mall`.`pms_category`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_category`)
UNION ALL SELECT 'pms_brand',
       (SELECT COUNT(*) FROM `mall`.`pms_brand`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_brand`);

-- check ② 内容对齐（只对行数相等还不够：错位/串行也会行数相等）
--    做法：按 id 关联、逐列比对，统计"每一列都相同"的行数——应当逐表等于该表行数。
SELECT 'pms_spu' AS tbl, COUNT(*) AS rows_matched_on_all_columns
FROM `mall`.`pms_spu` a JOIN `mall_product`.`pms_spu` b ON b.`id` = a.`id`
WHERE b.`category_id` <=> a.`category_id` AND b.`brand_id` <=> a.`brand_id`
  AND b.`title` <=> a.`title` AND b.`subtitle` <=> a.`subtitle`
  AND b.`main_image` <=> a.`main_image` AND b.`status` <=> a.`status`
  AND b.`recommended` <=> a.`recommended` AND b.`sales` <=> a.`sales`
  AND b.`deleted` <=> a.`deleted`
  AND b.`create_time` <=> a.`create_time` AND b.`update_time` <=> a.`update_time`
UNION ALL
SELECT 'pms_spu_detail', COUNT(*)
FROM `mall`.`pms_spu_detail` a JOIN `mall_product`.`pms_spu_detail` b ON b.`id` = a.`id`
WHERE b.`spu_id` <=> a.`spu_id` AND b.`description` <=> a.`description`
  AND b.`images` <=> a.`images` AND b.`params` <=> a.`params`
  AND b.`detail_html` <=> a.`detail_html` AND b.`update_time` <=> a.`update_time`
UNION ALL
SELECT 'pms_sku', COUNT(*)
FROM `mall`.`pms_sku` a JOIN `mall_product`.`pms_sku` b ON b.`id` = a.`id`
WHERE b.`spu_id` <=> a.`spu_id` AND b.`sku_code` <=> a.`sku_code`
  AND b.`spec_values` <=> a.`spec_values` AND b.`image` <=> a.`image`
  AND b.`price` <=> a.`price` AND b.`original_price` <=> a.`original_price`
  AND b.`stock` <=> a.`stock` AND b.`sales` <=> a.`sales`
  AND b.`status` <=> a.`status` AND b.`deleted` <=> a.`deleted`
  AND b.`create_time` <=> a.`create_time` AND b.`update_time` <=> a.`update_time`
UNION ALL
SELECT 'pms_sku_stock_log', COUNT(*)
FROM `mall`.`pms_sku_stock_log` a JOIN `mall_product`.`pms_sku_stock_log` b ON b.`id` = a.`id`
WHERE b.`sku_id` <=> a.`sku_id` AND b.`order_no` <=> a.`order_no`
  AND b.`change_type` <=> a.`change_type` AND b.`delta` <=> a.`delta`
  AND b.`before_stock` <=> a.`before_stock` AND b.`after_stock` <=> a.`after_stock`
  AND b.`operator_id` <=> a.`operator_id` AND b.`remark` <=> a.`remark`
  AND b.`create_time` <=> a.`create_time`
UNION ALL
SELECT 'pms_category', COUNT(*)
FROM `mall`.`pms_category` a JOIN `mall_product`.`pms_category` b ON b.`id` = a.`id`
WHERE b.`parent_id` <=> a.`parent_id` AND b.`name` <=> a.`name`
  AND b.`sort` <=> a.`sort` AND b.`status` <=> a.`status`
  AND b.`create_time` <=> a.`create_time` AND b.`update_time` <=> a.`update_time`
UNION ALL
SELECT 'pms_brand', COUNT(*)
FROM `mall`.`pms_brand` a JOIN `mall_product`.`pms_brand` b ON b.`id` = a.`id`
WHERE b.`name` <=> a.`name` AND b.`logo` <=> a.`logo`
  AND b.`sort` <=> a.`sort` AND b.`status` <=> a.`status`
  AND b.`create_time` <=> a.`create_time` AND b.`update_time` <=> a.`update_time`;

-- check ③ 幽灵行（dst 有、src 没有）——**必须逐表为 0**
--    这是 UPSERT 相对 P5"整表重灌"唯一让掉的保证，因此用查询补回来（见文件头【幂等做法】）。
SELECT 'pms_spu' AS tbl, COUNT(*) AS rows_only_in_dst
FROM `mall_product`.`pms_spu` b LEFT JOIN `mall`.`pms_spu` a ON a.`id` = b.`id` WHERE a.`id` IS NULL
UNION ALL SELECT 'pms_spu_detail', COUNT(*)
FROM `mall_product`.`pms_spu_detail` b LEFT JOIN `mall`.`pms_spu_detail` a ON a.`id` = b.`id` WHERE a.`id` IS NULL
UNION ALL SELECT 'pms_sku', COUNT(*)
FROM `mall_product`.`pms_sku` b LEFT JOIN `mall`.`pms_sku` a ON a.`id` = b.`id` WHERE a.`id` IS NULL
UNION ALL SELECT 'pms_sku_stock_log', COUNT(*)
FROM `mall_product`.`pms_sku_stock_log` b LEFT JOIN `mall`.`pms_sku_stock_log` a ON a.`id` = b.`id` WHERE a.`id` IS NULL
UNION ALL SELECT 'pms_category', COUNT(*)
FROM `mall_product`.`pms_category` b LEFT JOIN `mall`.`pms_category` a ON a.`id` = b.`id` WHERE a.`id` IS NULL
UNION ALL SELECT 'pms_brand', COUNT(*)
FROM `mall_product`.`pms_brand` b LEFT JOIN `mall`.`pms_brand` a ON a.`id` = b.`id` WHERE a.`id` IS NULL;
