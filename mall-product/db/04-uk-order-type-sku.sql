-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_product 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- P6-5 #2：`release` 幂等的**唯一索引兜底**（已存在的 mall_product 库升级用）
--
-- 背景（为什么要它）：
--   P6-4 把库存回补 `release` 从"本地事务内"改成了**跨进程 + 提交后调用**（trade 侧的
--   `StockReleaseAfterCommit`）。那个改动解决了"本地回滚后重试会**重复回补**"，但没有解决
--   **"重复调用本身"**：进程在"提交后调用"与"对账重试"两条路径上都可能把同一个
--   (orderNo, changeType) 回补第二次 ⇒ **库存凭空多**（超卖方向、不可逆）。
--   方案 §4.2 原本就写着"`release`/`unlock` 以 `orderNo` 为键，重复调用无副作用"。
--
-- 两层防护（本脚本只负责第二层）：
--   ① **应用层（主判据）**：`StockCommandServiceImpl#alreadyLogged`
--      在同一事务内 `SELECT ... FOR UPDATE` 查该三元组是否已有流水，命中则**跳过该行**（静默、不抛异常）；
--   ② **本脚本（兜底）**：唯一索引 `uk_order_type_sku` ⇒ 万一判据被绕过（新代码路径/并发），
--      重复插入直接报 1062，**绝不会静默多回补一次**。
--
-- 执行前自检（必须为 0，否则先人工处理重复行**再**加索引，不要为了加索引删数据）：
--   SELECT COUNT(*) FROM (
--     SELECT order_no, change_type, sku_id FROM mall_product.pms_sku_stock_log
--      WHERE order_no IS NOT NULL GROUP BY order_no, change_type, sku_id HAVING COUNT(*) > 1
--   ) t;
--   —— 2026-09-14 09:3x 实测 **0 组** ⇒ 可直接加索引（本脚本已在活库执行过一次，见下）
--
-- 幂等：重复执行会报 `Duplicate key name 'uk_order_type_sku'`（1061），属预期，可忽略。
--
-- 实测记录（2026-09-14 09:3x，主 agent 在活库执行）：
--   · ALTER 成功；`information_schema.STATISTICS` 复查：`uk_order_type_sku | order_no,change_type,sku_id | unique=YES`
--   · 用一条真实存在的三元组试插重复行 ⇒ `ERROR 1062 ... for key 'pms_sku_stock_log.uk_order_type_sku'`，**行数不变** ✓
-- =====================================================================

SET FOREIGN_KEY_CHECKS = 0;

ALTER TABLE `mall_product`.`pms_sku_stock_log`
    ADD UNIQUE KEY `uk_order_type_sku` (`order_no`, `change_type`, `sku_id`);

SET FOREIGN_KEY_CHECKS = 1;

-- 自检：索引必须在，且三元组上唯一的
SELECT IF(COUNT(*) = 1, 'OK: uk_order_type_sku 已存在', 'FAIL: 索引缺失') AS self_check
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'mall_product' AND TABLE_NAME = 'pms_sku_stock_log'
  AND INDEX_NAME = 'uk_order_type_sku';
