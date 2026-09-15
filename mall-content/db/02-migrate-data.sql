-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_content 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- 数据搬迁：mall.cms_* → mall_content.cms_*（P2）
--
-- 幂等：先 DELETE 再 INSERT，可重复执行（每次执行都以 mall 库为源做一次全量对齐）
-- 时机：**单体切换成"只转发"之前**再执行一次，避免拷贝与切换之间后台改动的丢失。
-- 执行： mysql -uroot -p < 02-migrate-data.sql
-- =====================================================================

DELETE FROM `mall_content`.`cms_banner`;
INSERT INTO `mall_content`.`cms_banner`
    (`id`, `title`, `image_url`, `link_url`, `sort`, `status`, `create_time`, `update_time`)
SELECT `id`, `title`, `image_url`, `link_url`, `sort`, `status`, `create_time`, `update_time`
FROM `mall`.`cms_banner`;

DELETE FROM `mall_content`.`cms_notice`;
INSERT INTO `mall_content`.`cms_notice`
    (`id`, `title`, `content`, `sort`, `status`, `publish_time`, `create_time`, `update_time`)
SELECT `id`, `title`, `content`, `sort`, `status`, `publish_time`, `create_time`, `update_time`
FROM `mall`.`cms_notice`;

-- 自增起点对齐旧表（否则新插入的 id 会从 1 开始，与已搬迁的历史 id 冲突）
ALTER TABLE `mall_content`.`cms_banner` AUTO_INCREMENT = 126;
ALTER TABLE `mall_content`.`cms_notice` AUTO_INCREMENT = 86;
