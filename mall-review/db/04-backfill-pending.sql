-- ⚠️ P8-5（2026-09-14）：mall_review.pms_comment 已随「mall 空库」一起删除；权威副本在 **mall_review.pms_comment**。
--    因此本脚本的来源表已改成 `mall_review.pms_comment`（回填逻辑一字未改）。
-- =====================================================================
-- 04-backfill-pending.sql —— P4-2 的 R1 要求：把**历史已评价**的订单明细回填进待评价读模型
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 04-backfill-pending.sql
--        （幂等：重复执行不会新增行、不会改变已有行，见下面的 ON DUPLICATE KEY UPDATE）
--
-- =====================================================================
-- 【为什么必须有这个脚本】R1：读模型一上线，"防重复评价"就只剩一条防线
-- ---------------------------------------------------------------------
-- 改造前，"这条明细是否已经评价过"由 oms_order_item.comment_status 表达；改造后由本服务
-- 自己的 review_pending_item.commented 表达（见 db/01 的建表注释）。而读模型是**从零开始**
-- 由 order.finished 事件投影出来的——历史明细没有事件，于是它们：
--   · 不在读模型里 → 既不会被判"已评价"，也可能被判成「评价条目不属于该订单」；
--   · 更糟的是，源库的 comment_status 本身就已经坏了：实测 2502 条 pms_comment 里
--     **2500 条**对应的 oms_order_item.comment_status 仍然是 0（只有 2 条是孤儿评价，
--     指向根本不存在的明细）。也就是说**今天的防重闸门对存量数据已经失效**，
--     对这批明细再发一次评价会同时通过快照预检与 CAS，写出第二条评价
--     （成因是演示数据生成器绕过服务直插，见 sql/data/01_mall_data.sql）。
-- 因此必须回填：让每一条"已经有评论的明细"在读模型里**已经**是 commented=1，
-- 它从此永远抢不到闸门（`UPDATE ... WHERE commented = 0` 影响 0 行 → 409）。
--
-- =====================================================================
-- 【跨 schema 读：本脚本刻意读 `mall`，不是失误】
-- ---------------------------------------------------------------------
-- 源数据在单体库：mall_review.pms_comment（评论）需要与 mall_trade.oms_order_item` /
-- mall_trade.oms_order 关联才能拿到目标表的 NOT NULL 列（订单号/会员/商品/收货时间）。
-- 这三张表都在 `mall` schema 下，而**目标表在 `mall_review`**，所以本脚本是跨 schema 的
-- "一次性迁移读 + 本库写"。这在迁移脚本里是可以接受的（迁移本就要同时看得见新旧两侧）：
--   · 它不是运行期依赖——服务代码里没有任何对 `mall` 库的访问（P4 的验收之一）；
--   · 它只执行一次，且单体的 `mall_review.pms_comment` 会在后续批次用 db/03-drop-from-mall.sql 移除；
--   · 本服务自己的 `mall_review.pms_comment` 已经有同一批 2502 行，但它**不能**当源：
--     订单侧的 oms_order / oms_order_item 只在 `mall` 里，而目标表的 NOT NULL 列要求
--     从订单侧取值（见下一节）。
--
-- =====================================================================
-- 【列值的来源：目标表 NOT NULL 的列一律取"订单侧的权威值"】
-- ---------------------------------------------------------------------
--   order_item_id ← oms_order_item.id        （主键 = 幂等去重键 + 抢闸门判定列）
--   order_no      ← oms_order_item.order_no  （权威：明细自己的订单号）
--   member_id     ← oms_order.member_id      （归属校验用，只能来自订单）
--   spu_id        ← oms_order_item.spu_id
--   spu_title     ← oms_order_item.spu_title （下单时的标题快照）
--   finished_time ← oms_order.finish_time    （"还剩几天可评价"的基准）
--   sku_id/sku_image/quantity ← oms_order_item 的下单快照
--   commented     ← 常量 1                   （本脚本的全部意义）
--   comment_id    ← min(pms_comment.id)      （该明细名下最早的那条评论，确定性取值）
-- 刻意**不**从 pms_comment 取 order_no/member_id/spu_id：那是评价行自己的冗余副本，
-- 而订单侧才是这三列的定义方（两者实测一致，mismatch=0，但取权威侧不依赖这个巧合）。
-- comment_id 用 min(id) 而不是 max(id)：同一明细有 506 组重复评论（一个 order_item_id 对应
-- 多条评论，共 2502 行 → 1887 个不同明细），取最早一条是确定性的，重复执行结果不变。
--
-- =====================================================================
-- 【范围：判据是"这条明细有评论"，**不**额外要求 order_status = 3(已完成)】
-- ---------------------------------------------------------------------
-- 实测 2500 条可关联评论里，2478 条订单是 3(已完成)，另有 22 条处于 6(退款中)/7(已退款)。
-- 这 22 条同样落进回填范围，理由：本脚本要守的不变量是"已经有评论的明细不能再被评价"，
-- 它与订单当前状态无关；反过来，漏掉它们就等于给这 22 条留下第二次评价的机会。
-- 另一条硬约束：目标表 finished_time 是 NOT NULL，而实测这 2500 行 finish_time 全部非空
-- （所以没有"状态不符就补不上时间"的问题）。
--
-- =====================================================================
-- 【刻意不做的事】
-- ---------------------------------------------------------------------
--   1. **不**回写 mall_trade.oms_order_item`.`comment_status`。它是订单域的表，"存量 status=0"
--      这件事要在订单域自己的迁移里收口（本批不动订单域，也不改数据语义）；
--      本脚本只让 review 的闸门重新有效，两个域各自负责自己的列。
--   2. **不**为 2 条孤儿评价造行。它们连订单明细都不存在（详见文件末尾的自检 ④），
--      目标表的 NOT NULL 列没有任何权威来源，硬造出来的 order_item_id 反而可能与
--      将来真实的明细 ID 撞车。它们也无法被"重新评价"——归属校验根本找不到这条明细。
--   3. **不**删除任何表、任何行（P4 的纪律：回退 = 改网关路由）。
--
-- =====================================================================
-- 【幂等：靠主键 + "无有意义变化"的 ON DUPLICATE KEY UPDATE】
-- ---------------------------------------------------------------------
--   · 已存在的行：commented 置 1（收敛到真相）、comment_id 只在为空时补上
--     （COALESCE：**绝不**覆盖已经抢到闸门写进去的真实 comment_id，那会破坏对账）；
--   · 重复执行时，已经 commented=1 且 comment_id 非空的行不产生任何变化
--     ——MySQL 会判定"该行未改变"，连 update_time 都不会动。
-- 已实测（2026-09-13）：本机 MySQL 8.0.42 的 `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE`
-- 允许在 UPDATE 子句里用 SELECT 的**源表别名**（s.comment_id）；同时刻意不使用
-- MySQL 8.0.20 起已废弃的 VALUES() 写法。
-- =====================================================================

INSERT INTO `mall_review`.`review_pending_item`
    (`order_item_id`, `order_no`, `member_id`, `spu_id`, `sku_id`, `spu_title`, `sku_image`,
     `quantity`, `finished_time`, `commented`, `comment_id`)
SELECT s.`order_item_id`,
       s.`order_no`,
       s.`member_id`,
       s.`spu_id`,
       s.`sku_id`,
       s.`spu_title`,
       s.`sku_image`,
       s.`quantity`,
       s.`finished_time`,
       1,
       s.`comment_id`
  FROM (
        SELECT i.`id`          AS `order_item_id`,
               i.`order_no`    AS `order_no`,
               o.`member_id`   AS `member_id`,
               i.`spu_id`      AS `spu_id`,
               i.`sku_id`      AS `sku_id`,
               i.`spu_title`   AS `spu_title`,
               i.`sku_image`   AS `sku_image`,
               i.`quantity`    AS `quantity`,
               o.`finish_time` AS `finished_time`,
               (SELECT MIN(c.`id`) FROM mall_review.pms_comment c
                 WHERE c.`order_item_id` = i.`id`) AS `comment_id`
          FROM mall_trade.oms_order_item i
          JOIN mall_trade.oms_order o ON o.`order_no` = i.`order_no`
         WHERE EXISTS (SELECT 1 FROM mall_review.pms_comment c
                        WHERE c.`order_item_id` = i.`id`)
       ) AS s
ON DUPLICATE KEY UPDATE
    `commented`  = 1,
    -- 目标列必须写全限定名：派生表 s 里也有同名的 comment_id，
    -- 不限定会直接报 `ERROR 1052: Column 'comment_id' in field list is ambiguous`（实测踩过）
    `comment_id` = COALESCE(`mall_review`.`review_pending_item`.`comment_id`, s.`comment_id`);

-- =====================================================================
-- 自检：执行完看这四段输出（本机 2026-09-13 的实测值写在注释里，数字变了要解释）
-- =====================================================================

-- ① 回填结果总览：distinct_items 应等于"可关联的已评价明细数" 1885
--    （源 pms_comment 的 distinct order_item_id 是 1887，差的 2 个就是孤儿，见 ④）
SELECT COUNT(*)                        AS rows_total,
       COUNT(DISTINCT `order_item_id`) AS distinct_items,
       SUM(`commented` = 1)            AS commented_rows,
       SUM(`comment_id` IS NOT NULL)   AS with_comment_id
  FROM `mall_review`.`review_pending_item`;

-- ② 不变量：**没有任何一行**是"有评论但 commented=0"（必须是 0）
SELECT COUNT(*) AS violated_rows
  FROM `mall_review`.`review_pending_item` p
 WHERE p.`commented` = 0
   AND EXISTS (SELECT 1 FROM mall_review.pms_comment c WHERE c.`order_item_id` = p.`order_item_id`);

-- ③ 覆盖度：下面这个数必须是 0 = "每一条可关联的已评价明细都在读模型里且已标记已评价"
SELECT COUNT(*) AS uncovered_commented_items
  FROM (SELECT DISTINCT c.`order_item_id`
          FROM mall_review.pms_comment c
          JOIN mall_trade.oms_order_item i ON i.`id` = c.`order_item_id`) src
 WHERE NOT EXISTS (SELECT 1 FROM `mall_review`.`review_pending_item` p
                    WHERE p.`order_item_id` = src.`order_item_id` AND p.`commented` = 1);

-- ④ 孤儿（预期 2 行：DEMO-ORD-88007/990007、DEMO-ORD-88011/990011，订单与明细都不存在）
--    它们不在回填范围（无权威列值可用），也无法被重复评价（归属校验找不到明细）。
SELECT c.`id` AS comment_id, c.`order_no`, c.`order_item_id`, c.`member_id`, c.`spu_id`
  FROM mall_review.pms_comment c
  LEFT JOIN mall_trade.oms_order_item i ON i.`id` = c.`order_item_id`
 WHERE i.`id` IS NULL;
