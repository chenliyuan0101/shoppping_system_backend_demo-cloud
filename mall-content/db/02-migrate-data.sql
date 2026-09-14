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
