-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_review 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- ⚠️ P8-5（2026-09-14）：本文件是**历史手工脚本**（`mall` 里的源表已随空库删除）。
--    留档用途：记录当时怎么迁的；**不要再执行**（结构权威在 src/main/resources/db/migration 或各服务 db/01）。
-- =====================================================================
-- mall-review 的库与表（P4 第 1 批：先把"库 + 骨架 + 只读切片"立起来）
--
-- 生成方式：`pms_comment` 的表结构由**现网表直接导出**，逐字照抄，不做任何"顺手优化"——
--   D:\MySQL\MySQL\bin\mysqldump.exe -uroot -p123456 --no-data --skip-comments \
--       --skip-add-locks --no-tablespaces mall pms_comment
--   P4 的目标是搬走**所有权**，不是改表；结构差异会让"回退=改网关路由"这句话不成立。
--   （唯一的例外是下面的"新增列 ①"，它是 P4 方案明确要求补的字段，见下。）
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 01-mall_review-schema.sql
--        （幂等：CREATE DATABASE/TABLE IF NOT EXISTS，可重复执行）
-- =====================================================================
CREATE DATABASE IF NOT EXISTS `mall_review`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

-- ---------------------------------------------------------------------
-- pms_comment（商品评价表）—— 逐字导出自 mall_review.pms_comment，两处刻意的改动：
--
-- 【改动 ①：新增列 member_nickname（昵称冗余快照）】
--   方案 §4.5 ③ 与 §2.7 的"历史快照"语义要求：评论展示**不能**再向 user-center 要昵称。
--   今天单体 pms 的商品详情页评论列表是"查 pms_comment 再按 member_id 去 ums_member 取昵称"
--   （见方案 §2.3 里 `ums_member` 的跨域访问方"pms（评论昵称）"）——这条跨库读正是 P4 要拆掉的耦合。
--   因此本表自带一份昵称快照：写入时落库，之后**不随会员改名而变**（历史快照语义）。
--   放在 member_id 之后只是为了可读（其余列的顺序与导出逐字一致）。
--   存量数据的回填见 db/02-migrate-data.sql。
--
-- 【改动 ②：**没有** UNIQUE KEY(order_item_id)，这是刻意的】
--   方案 §4.5 原设计是"抢占用本地唯一键 INSERT pms_comment(order_item_id UNIQUE) 冲突 → 409"。
--   实测（现网源库 mall_review.pms_comment，2026-09-13，见 .dsh-notes/P4-remaining-plan.md 发现 1）：
--       · 总行数 2502，distinct order_item_id = 1887 → 重复组 506 个（约 615 行与别人共用 order_item_id）；
--       · 现有索引只有 PRIMARY(id)、idx_member_id、idx_spu_status(spu_id,status)，本来就没有 order_item_id 唯一键；
--   结论：**加这个唯一键会直接失败**，而清洗这些重复行属于改数据（哪些留、哪些删需要业务判断，
--   且这些重复很可能是演示数据生成器绕过服务直插造成的）。
--   因此"防重复"的闸门改放在 review 自己的待评价读模型上（见下面的 review_pending_item），
--   唯一键降级为**后续加固**：先清洗重复行，再补 UNIQUE(order_item_id)，不阻塞 P4。
--   现有三个键**原样保留**，一个不动。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mall_review`.`pms_comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `member_id` bigint NOT NULL COMMENT '会员ID',
  `member_nickname` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '会员昵称快照(写入时落库,不随改名变化,P4 新增)',
  `order_no` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号(评价来源)',
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID(一单一评,关联粒度)',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID(可选)',
  `rating` tinyint NOT NULL DEFAULT '5' COMMENT '评分 1-5',
  `content` varchar(1000) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '评价内容',
  `images` json DEFAULT NULL COMMENT '晒图URL数组 JSON',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0待审核 1展示 2隐藏',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除 0否 1是',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_spu_status` (`spu_id`,`status`),
  KEY `idx_member_id` (`member_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2668 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='商品评价表';

-- ---------------------------------------------------------------------
-- review_pending_item（待评价读模型）—— P4 **新增**的表，没有源表可抄。
--
-- 【它解决什么】改造前"提交评价"要跨域查 oms_order（归属/状态）+ 跨域 CAS 写
--   oms_order_item.comment_status（兼作并发防重）——这是 pms→oms 环里最硬的一条边。
--   改造后：trade 在"确认收货"时发 order.finished{orderNo, memberId, finishedTime,
--   items[{orderItemId, spuId, skuId, spuTitle, skuImage, quantity}]}（方案 §4.5），
--   review 消费后写进本表；提交评价时**只查本地**，零跨服务调用。
--
-- 【为什么 order_item_id 是主键 —— 这是本表的全部设计要点】
--   ① 幂等去重：事件可能重复投递（at-least-once）。order_item_id 做主键 + INSERT IGNORE /
--      ON DUPLICATE KEY UPDATE，重复事件天然只落一行，不需要额外的幂等表或 Redis 幂等键。
--   ② **并发闸门**：防重复评价不再靠 pms_comment 的唯一键（见上面改动 ②），而是靠一次条件更新：
--          UPDATE review_pending_item SET commented = 1, comment_id = ?
--           WHERE order_item_id = ? AND commented = 0      -- 影响行数 = 抢闸门结果
--      影响行数 1 = 抢到（可以继续写评论）；影响行数 0 = 已被抢（409「同一订单明细不能重复评价」）。
--      这与 P3 的购物车闸门（"条件删除 + 影响行数即并发凭证"，方案 §4.1 ①）**同构**：
--      把"并发凭证"放在一条 UPDATE 的返回行数上，而不是放在"先 SELECT 再 INSERT"的窗口里。
--      主键（唯一索引）是这条 UPDATE 能一行原子判定的前提，所以 order_item_id 必须是主键。
--
-- 【与方案 §2.3 的字段差异】方案里写的列名是 `comment_status`；这里按 P4 第 1 批的任务口径
--   拆成 `commented`（0/1，闸门用的条件列）+ `comment_id`（抢成功后写入的评论 id，便于对账）。
--   两者信息量更大：`comment_status` 无法回答"这条明细最终评的是哪条评论"。
--   `idx_member_commented(member_id, commented)` 是"我的待评价列表"（member_id + commented=0）的支撑索引。
--
-- 【本批不灌数据】读模型由 order.finished 事件驱动写入，事件与消费在 P4 后续批次接。
--   本批只建表，因此表是空的——"待评价列表"要等事件接线后才有内容。
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `mall_review`.`review_pending_item` (
  `order_item_id` bigint NOT NULL COMMENT '订单明细ID(主键:事件幂等的天然去重键 + 抢闸门的判定列)',
  `order_no` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '订单号',
  `member_id` bigint NOT NULL COMMENT '会员ID(评价归属校验用)',
  `spu_id` bigint NOT NULL COMMENT 'SPU ID',
  `sku_id` bigint DEFAULT NULL COMMENT 'SKU ID(可选)',
  `spu_title` varchar(255) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '商品标题快照',
  `sku_image` varchar(500) COLLATE utf8mb4_general_ci DEFAULT NULL COMMENT 'SKU 图片快照',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '购买数量',
  `finished_time` datetime NOT NULL COMMENT '订单确认收货时间(算"剩余可评价天数"的基准)',
  `commented` tinyint NOT NULL DEFAULT '0' COMMENT '是否已评价 0否 1是(抢闸门的条件列)',
  `comment_id` bigint DEFAULT NULL COMMENT '抢闸门成功后写入的评价ID(对账用)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`order_item_id`),
  KEY `idx_member_commented` (`member_id`,`commented`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='待评价读模型(order.finished 事件的投影)';

-- =====================================================================
-- 自检 SQL（执行建库后跑一遍，验证"改动 ②"确实是"不加唯一键"，
-- 并随时复核那条实测结论——数字变了就说明数据被清洗过、可以重新考虑加唯一键了）
-- =====================================================================
-- ① 现有索引：应看到 PRIMARY / idx_spu_status / idx_member_id，**没有**任何 order_item_id 唯一键
-- SHOW INDEX FROM `mall_review`.`pms_comment`;
--
-- ② 重复度实测：rows=2502 / distinct_items=1887 / 重复组=506（加 UNIQUE 会失败的原因）
-- SELECT COUNT(*)                                     AS rows_total,
--        COUNT(DISTINCT `order_item_id`)              AS distinct_items,
--        COUNT(*) - COUNT(DISTINCT `order_item_id`)   AS dup_extra_rows
-- FROM mall_review.pms_comment;
-- SELECT COUNT(*) AS dup_groups FROM (
--     SELECT `order_item_id` FROM mall_review.pms_comment
--      GROUP BY `order_item_id` HAVING COUNT(*) > 1) t;
--
-- ③ review_pending_item 的闸门列与主键（order_item_id 必须是 PK，否则条件更新不成立）
-- SHOW INDEX FROM `mall_review`.`review_pending_item`;
