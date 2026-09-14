-- =====================================================================
-- P8-4：每服务一个 **独立 schema 账号**（最小权限）
-- 目标：本服务的账号**只能**碰自己的库，碰别的库必须被 MySQL 拒绝（P8 验收第 3 条）。
-- ⚠️ 这是**演示/本机**口令（开发用）；生产走密钥管理，不写在文件里。
-- ⚠️ 纯增量动作：不改变任何现有连接（各服务仍用 root 跑），切换账号属于下一批"运行时接线"。
-- 生成时间：2026-09-14 11:40:55
-- =====================================================================

-- ---- mall_user ：只对 mall_user 有读写，其他库一律无权限 ----
-- ⚠️ 不要再加 REVOKE ALL PRIVILEGES ON *.*：CREATE USER 本来就是**零权限**，而那句 REVOKE 会把下面刚授的库级权限一起抹掉（实测：SHOW GRANTS 只剩 USAGE ON *.*）。
CREATE USER IF NOT EXISTS 'mall_user'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_user'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_user`.* TO 'mall_user'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_user`.* TO 'mall_user'@'localhost';

-- ---- mall_product ：只对 mall_product 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_product'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_product'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_product`.* TO 'mall_product'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_product`.* TO 'mall_product'@'localhost';

-- ---- mall_marketing ：只对 mall_marketing 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_marketing'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_marketing'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_marketing`.* TO 'mall_marketing'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_marketing`.* TO 'mall_marketing'@'localhost';

-- ---- mall_review ：只对 mall_review 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_review'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_review'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_review`.* TO 'mall_review'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_review`.* TO 'mall_review'@'localhost';

-- ---- mall_content ：只对 mall_content 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_content'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_content'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_content`.* TO 'mall_content'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_content`.* TO 'mall_content'@'localhost';

-- ---- mall_admin ：只对 mall_admin 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_admin'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_admin'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_admin`.* TO 'mall_admin'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_admin`.* TO 'mall_admin'@'localhost';

-- ---- mall_trade ：只对 mall_trade 有读写，其他库一律无权限 ----
CREATE USER IF NOT EXISTS 'mall_trade'@'%' IDENTIFIED BY 'dev-only-2026';
CREATE USER IF NOT EXISTS 'mall_trade'@'localhost' IDENTIFIED BY 'dev-only-2026';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_trade`.* TO 'mall_trade'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP ON `mall_trade`.* TO 'mall_trade'@'localhost';

-- ---- 自检：打印每个账号被授予的库级权限（应只有自己那一个库）----
SHOW GRANTS FOR 'mall_user'@'%';
SHOW GRANTS FOR 'mall_product'@'%';
SHOW GRANTS FOR 'mall_marketing'@'%';
SHOW GRANTS FOR 'mall_review'@'%';
SHOW GRANTS FOR 'mall_content'@'%';
SHOW GRANTS FOR 'mall_admin'@'%';
SHOW GRANTS FOR 'mall_trade'@'%';
