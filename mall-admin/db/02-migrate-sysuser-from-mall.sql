-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_admin 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- P7 / mall-admin：把 mall.sys_user 的那 1 行复制进 mall_admin.sys_user
--
-- ⚠️⚠️ 过渡期的"一个真相"纪律（P3/P5 已经因为这个坑返工过两次）
--   本脚本执行后，`mall_admin.sys_user` 是 `mall.sys_user` 的**副本**，不是权威：
--   在 `/api/admin/auth/**` 的路由切到 mall-admin 之前，单体仍然是**唯一权威**
--   （后台登录、AdminAuthInterceptor 查的都是 mall 库）。
--   ⇒ 因此：
--     ① 本脚本**必须可重复执行**（幂等）：源库改了再跑一次，目标库就对齐；
--     ② **切路由之前必须重跑一次本脚本**（否则切过去的瞬间，管理员口令/状态是旧的）；
--     ③ 本脚本**只读** `mall.sys_user`，**只写** `mall_admin.sys_user` —— 绝不写源库。
--   为什么不能用"双向同步"或"两边都活"：那是两套真相，禁用/改密会互相覆盖，
--   排查时也说不清"这条记录是谁写的"（P5 券表的教训）。
--
-- 幂等实现：
--   · 复制：INSERT ... SELECT ... ON DUPLICATE KEY UPDATE（按主键 id 命中唯一键）
--   · 对齐：删除目标库"源库里已经不存在"的行（**带保护**：源库为空时一行都不删，
--           否则源库读空/连错库会把目标库清空）
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 02-migrate-sysuser-from-mall.sql
-- =====================================================================
SET NAMES utf8mb4;

-- ⚠️ 必须显式选库：MySQL 的**多表 DELETE**（下面的第 2 步）要求存在默认库，
--    否则报 `ERROR 1046 (3D000): No database selected` —— 哪怕每个表名都写了库限定符也一样。
--    实测踩过：脚本跑到第 2 步就中断（此时第 1 步已经插入成功），看起来像"迁移成功了一半"。
USE `mall_admin`;

-- ---------- 1) 复制（幂等：重复执行结果相同）----------
INSERT INTO `mall_admin`.`sys_user`
    (`id`, `username`, `password`, `nickname`, `status`, `create_time`, `update_time`)
SELECT `id`, `username`, `password`, `nickname`, `status`, `create_time`, `update_time`
FROM `mall`.`sys_user`
ON DUPLICATE KEY UPDATE
    `username`    = VALUES(`username`),
    `password`    = VALUES(`password`),
    `nickname`    = VALUES(`nickname`),
    `status`      = VALUES(`status`),
    `create_time` = VALUES(`create_time`),
    -- update_time 也照抄源值：它是"源库里那次修改发生的时刻"，
    -- 用 NOW() 会把"最后改动时间"改写成迁移时间，让两边对不上（保真度判据就废了）。
    `update_time` = VALUES(`update_time`);

-- ---------- 2) 对齐：目标库里"源库已删除"的行（带空源保护）----------
-- ⚠️ 只在源库非空时才删：源库为空可能是"连错库/权限不足导致读到 0 行"，
--    那种情况下把目标库删空 = 把管理端登录凭证抹掉（不可逆）。
DELETE t FROM `mall_admin`.`sys_user` t
LEFT JOIN `mall`.`sys_user` s ON s.`id` = t.`id`
WHERE s.`id` IS NULL
  AND (SELECT COUNT(*) FROM `mall`.`sys_user`) > 0;

-- ---------- 3) 自检：行数（源 vs 目标）----------
SELECT '源库 mall.sys_user'      AS side, COUNT(*) AS rows_cnt, MIN(`id`) AS min_id, MAX(`id`) AS max_id,
       SUM(`status` = 1) AS enabled_cnt, SUM(`status` = 0) AS disabled_cnt
FROM `mall`.`sys_user`
UNION ALL
SELECT '目标 mall_admin.sys_user' AS side, COUNT(*) AS rows_cnt, MIN(`id`) AS min_id, MAX(`id`) AS max_id,
       SUM(`status` = 1) AS enabled_cnt, SUM(`status` = 0) AS disabled_cnt
FROM `mall_admin`.`sys_user`;

-- ---------- 4) 自检：逐行内容差异（期望 0 行）----------
SELECT s.`id`, s.`username` AS src_username, t.`username` AS dst_username,
       s.`status` AS src_status, t.`status` AS dst_status,
       (s.`password` = t.`password`) AS pwd_same,
       (s.`nickname` = t.`nickname`) AS nick_same
FROM `mall`.`sys_user` s
LEFT JOIN `mall_admin`.`sys_user` t ON t.`id` = s.`id`
WHERE t.`id` IS NULL
   OR t.`username` <> s.`username`
   OR t.`password` <> s.`password`
   OR t.`nickname` <> s.`nickname`
   OR t.`status` <> s.`status`
   OR NOT (t.`create_time` <=> s.`create_time`)
   OR NOT (t.`update_time` <=> s.`update_time`);

-- ---------- 5) 自检：行数结论（源 = 目标 才算通过）----------
SELECT
    (SELECT COUNT(*) FROM `mall`.`sys_user`)       AS src_rows,
    (SELECT COUNT(*) FROM `mall_admin`.`sys_user`) AS dst_rows,
    CASE WHEN (SELECT COUNT(*) FROM `mall`.`sys_user`)
            = (SELECT COUNT(*) FROM `mall_admin`.`sys_user`)
         THEN 'ROWCOUNT OK' ELSE 'ROWCOUNT MISMATCH' END AS self_check;

-- ---------- 6) 自检：内容结论（第 4 步 0 行 + 行数相等 才算通过）----------
SELECT
    (SELECT COUNT(*) FROM (
        SELECT s.`id` FROM `mall`.`sys_user` s
        LEFT JOIN `mall_admin`.`sys_user` t ON t.`id` = s.`id`
        WHERE t.`id` IS NULL
           OR t.`username` <> s.`username`
           OR t.`password` <> s.`password`
           OR t.`nickname` <> s.`nickname`
           OR t.`status` <> s.`status`
           OR NOT (t.`create_time` <=> s.`create_time`)
           OR NOT (t.`update_time` <=> s.`update_time`)
    ) diff) AS content_diff_rows,
    CASE WHEN (SELECT COUNT(*) FROM `mall`.`sys_user`)
              = (SELECT COUNT(*) FROM `mall_admin`.`sys_user`)
          AND (SELECT COUNT(*) FROM (
                SELECT s.`id` FROM `mall`.`sys_user` s
                LEFT JOIN `mall_admin`.`sys_user` t ON t.`id` = s.`id`
                WHERE t.`id` IS NULL
                   OR t.`username` <> s.`username`
                   OR t.`password` <> s.`password`
                   OR t.`nickname` <> s.`nickname`
                   OR t.`status` <> s.`status`
                   OR NOT (t.`create_time` <=> s.`create_time`)
                   OR NOT (t.`update_time` <=> s.`update_time`)
              ) diff2) = 0
         THEN 'MIGRATION OK（源=目标，逐行逐列一致）'
         ELSE 'MIGRATION MISMATCH（见上面第 3/4 步的输出）' END AS self_check;

-- ---------- 7) 提醒：目标库里**只应有** sys_user 一张表 ----------
SELECT `TABLE_NAME` AS mall_admin_tables FROM `information_schema`.`TABLES`
WHERE `TABLE_SCHEMA` = 'mall_admin' ORDER BY `TABLE_NAME`;
