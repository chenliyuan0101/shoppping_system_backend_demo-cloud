-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_product 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- 02-verify-counts.sql —— **只读**的行数对齐查询（规格 §3 允许的"可选脚本"）
--
-- 为什么单独有这个文件（而不是只用 db/02-migrate-data.sql 里的自检）：
--   · db/02 会**写**新库；验收方（主 agent）要能"只读地"复跑一遍对齐数字，
--     这一条命令就够，不必冒着重跑迁移的风险；
--   · 它把**基线数字**（P6-1 规格 §2，来自 .dsh-notes/P6-0-baseline-main-agent.md 的实测）
--     一起写进结果列，判据是"dst_rows 与 baseline 相等"，读的人不用去翻文档对数字。
--
-- ⚠️ 本文件**不含任何写操作**（只有 SELECT）；对 mall 库只读，对 mall_product 也只读。
-- ⚠️ 判据必须用 COUNT(*)：information_schema.TABLES.TABLE_ROWS 是**估算值**
--    （P6-0 实测 pms_sku 估 2393 / 实际 2438、pms_comment 估 2636 / 实际 2502），
--    用它会得到"永远对不上"的假差异。本文件刻意不查 TABLE_ROWS。
--
-- 执行： $env:MYSQL_PWD='123456'; D:\MySQL\bin\mysql.exe -uroot -t --default-character-set=utf8mb4 < 02-verify-counts.sql
-- =====================================================================

-- ① 逐表行数 + 与 P6-1 基线比对
--    ⚠️ 两个判据要分清（P6-1 实测踩到过：基线与源库不再相等）：
--      · `src_eq_dst` = **"这次拷贝对不对"**——这是迁移本身的验收判据，**必须逐表 YES**；
--      · `aligned`    = **"目标库是否等于 §2 的基线数字"**——它是**冻结的历史快照**。
--        若 `src_eq_dst=YES` 而 `aligned=NO`，说明**基线过期了**（源库仍被单体写入），
--        不是迁移错了：此时应重新采一次基线，**不要**删数据去凑旧数字。
--        实测：`pms_sku_stock_log` 基线 2441，源库现在 2469（+28 = 04:08 那批探针产生的真实流水）。
SELECT 'pms_spu' AS tbl, 1508 AS baseline,
       (SELECT COUNT(*) FROM `mall`.`pms_spu`)            AS src_rows,
       (SELECT COUNT(*) FROM `mall_product`.`pms_spu`)    AS dst_rows,
       IF((SELECT COUNT(*) FROM `mall`.`pms_spu`) = (SELECT COUNT(*) FROM `mall_product`.`pms_spu`), 'YES', 'NO') AS src_eq_dst,
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_spu`) = 1508, 'YES', 'NO') AS aligned
UNION ALL
SELECT 'pms_spu_detail', 1508,
       (SELECT COUNT(*) FROM `mall`.`pms_spu_detail`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_spu_detail`),
       IF((SELECT COUNT(*) FROM `mall`.`pms_spu_detail`) = (SELECT COUNT(*) FROM `mall_product`.`pms_spu_detail`), 'YES', 'NO'),
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_spu_detail`) = 1508, 'YES', 'NO')
UNION ALL
SELECT 'pms_sku', 2438,
       (SELECT COUNT(*) FROM `mall`.`pms_sku`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_sku`),
       IF((SELECT COUNT(*) FROM `mall`.`pms_sku`) = (SELECT COUNT(*) FROM `mall_product`.`pms_sku`), 'YES', 'NO'),
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_sku`) = 2438, 'YES', 'NO')
UNION ALL
SELECT 'pms_sku_stock_log', 2441,
       (SELECT COUNT(*) FROM `mall`.`pms_sku_stock_log`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_sku_stock_log`),
       IF((SELECT COUNT(*) FROM `mall`.`pms_sku_stock_log`) = (SELECT COUNT(*) FROM `mall_product`.`pms_sku_stock_log`), 'YES', 'NO'),
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_sku_stock_log`) = 2441, 'YES', 'NO')
UNION ALL
SELECT 'pms_category', 69,
       (SELECT COUNT(*) FROM `mall`.`pms_category`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_category`),
       IF((SELECT COUNT(*) FROM `mall`.`pms_category`) = (SELECT COUNT(*) FROM `mall_product`.`pms_category`), 'YES', 'NO'),
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_category`) = 69, 'YES', 'NO')
UNION ALL
SELECT 'pms_brand', 46,
       (SELECT COUNT(*) FROM `mall`.`pms_brand`),
       (SELECT COUNT(*) FROM `mall_product`.`pms_brand`),
       IF((SELECT COUNT(*) FROM `mall`.`pms_brand`) = (SELECT COUNT(*) FROM `mall_product`.`pms_brand`), 'YES', 'NO'),
       IF((SELECT COUNT(*) FROM `mall_product`.`pms_brand`) = 46, 'YES', 'NO');

-- ② 白名单校验：本服务库里**不该**出现第 7 张 pms_* 表
--    （pms_comment 属评价域，P4 已搬去 mall_review；P8 前那一份回滚副本仍在 mall 库里）
SELECT COUNT(*) AS extra_pms_tables_in_mall_product
FROM `information_schema`.`TABLES`
WHERE `TABLE_SCHEMA` = 'mall_product' AND `TABLE_NAME` LIKE 'pms%'
  AND `TABLE_NAME` NOT IN ('pms_spu','pms_spu_detail','pms_sku','pms_sku_stock_log','pms_category','pms_brand');
-- ⇒ 期望 0

-- ③ 反向确认：mall 库里的两张"别人的表"没有被我们碰过（只读计数，不比对内容）
SELECT 'pms_comment(评价域,不迁)' AS tbl, COUNT(*) AS src_rows FROM `mall`.`pms_comment`;
