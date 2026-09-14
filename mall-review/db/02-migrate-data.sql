-- ⚠️ P8-5（2026-09-14）：本文件是**历史手工脚本**（`mall` 里的源表已随空库删除）。
--    留档用途：记录当时怎么迁的；**不要再执行**（结构权威在 src/main/resources/db/migration 或各服务 db/01）。
-- =====================================================================
-- 数据搬迁：mall_review.pms_comment → mall_review.pms_comment（P4 第 1 批）
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 02-migrate-data.sql
--
-- ⚠️ 过渡期（P4 全阶段）单体 pms 仍是 mall_review.pms_comment 的**写入方**
--    （评价提交还没搬过来，见 .dsh-notes/P4-remaining-plan.md）。
--    因此本脚本要在"切换前再执行一次"把期间的新数据补齐；脚本幂等：先 DELETE 再全量 INSERT。
--
-- 【昵称快照的来路】member_nickname 在源表里**不存在**（见 01 脚本头部改动 ①），
--    这里用 LEFT JOIN mall_user.ums_member 回填：
--      · COALESCE(m.nickname, '') —— 会员查不到时写**空字符串**而不是 NULL
--        （列是 NOT NULL DEFAULT ''；下游展示拿到空串就走"匿名/已注销"的兜底，不必再判空）；
--      · **不过滤 ums_member.deleted** —— 这是历史快照：会员已被逻辑删除，
--        他当年那条评价的昵称仍然要显示（评论是历史事实，不该因为会员注销而变成空白）；
--      · 实测（2026-09-13）2502 行评论的 member_id **全部**能在 mall_user.ums_member 命中，
--        因此本次回填的"未知"行数为 0；LEFT JOIN 仍然保留，是为了这个脚本在
--        "会员还没搬完 / 数据不一致"的环境里也不会丢行（丢行比空昵称严重得多）。
--
-- 【本脚本不做的两件事】——都不是遗漏，是分工：
--  1) 不灌 review_pending_item：它是**事件驱动的读模型**，由 trade 的 order.finished
--     经 MQ 投影而来，不能用 SQL 从存量数据"猜"出来（存量订单里哪些该算"待评价"
--     需要 trade 的口径，且 comment_status 语义正在变）。事件接线在 P4 后续批次。
--  2) 不删 mall_review.pms_comment：见 03-drop-from-mall.sql（不可逆，单独一步执行）。
-- =====================================================================

USE `mall_review`;

SET FOREIGN_KEY_CHECKS = 0;

-- 幂等：整表重灌（本表在 P4 期间只有本脚本与单体写入，没有 review 侧的增量可丢）
DELETE FROM `pms_comment`;

INSERT INTO `pms_comment`
    (`id`, `member_id`, `member_nickname`, `order_no`, `order_item_id`, `spu_id`, `sku_id`,
     `rating`, `content`, `images`, `status`, `deleted`, `create_time`)
SELECT c.`id`,
       c.`member_id`,
       COALESCE(m.`nickname`, '') AS `member_nickname`,
       c.`order_no`,
       c.`order_item_id`,
       c.`spu_id`,
       c.`sku_id`,
       c.`rating`,
       c.`content`,
       c.`images`,
       c.`status`,
       c.`deleted`,
       c.`create_time`
FROM mall_review.pms_comment c
         LEFT JOIN `mall_user`.`ums_member` m ON m.`id` = c.`member_id`;

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- 自检（执行后应看到三行完全一致的数字；源库行数随单体继续写入可能增长）
-- =====================================================================
SELECT (SELECT COUNT(*) FROM mall_review.pms_comment)                        AS src_rows,
       (SELECT COUNT(*) FROM `mall_review`.`pms_comment`)                  AS dst_rows,
       (SELECT COUNT(*) FROM `mall_review`.`pms_comment`
         WHERE `member_nickname` = '')                                     AS nickname_unknown,
       (SELECT COUNT(DISTINCT `order_item_id`) FROM mall_review.pms_comment)   AS src_distinct_items;
