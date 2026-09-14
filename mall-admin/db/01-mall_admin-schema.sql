-- =====================================================================
-- mall-admin 的库与表（P7：管理端 BFF 拆库）
--
-- 生成方式（**不是手写的**）：
--   mysql -uroot -p --default-character-set=utf8mb4 -N -e "SHOW CREATE TABLE mall.sys_user\G"
--   把输出原样贴进来，只做两处**机械**修改：
--     ① 表名加库限定符 `mall_admin`.`sys_user`（源库里的写法是裸 `sys_user`）；
--     ② 去掉表选项 `AUTO_INCREMENT=2`（那是**数据相关的运行时值**，不是结构；
--        迁移脚本用显式 id 插入，计数器会自行跟上）。
--   ⚠️ 一列、一个索引、一条 COMMENT 都没有改动 —— P7 的目标是搬走所有权，不是改表；
--      结构差异会让"回退 = 把网关路由改回单体"这句话不成立。
--
-- 范围（P7 §0/§1）：**只有 sys_user 一张表**。
--   别的域的表（会员/商品/订单/券/评价/内容…）**一张都不搬**：
--   管理端 BFF 不持有别人的数据，看板/会员列表走远程调用（P7 后半）。
--
-- 执行： mysql -uroot -p --default-character-set=utf8mb4 < 01-mall_admin-schema.sql   （幂等，可重复执行）
-- =====================================================================
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `mall_admin`
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `mall_admin`.`sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(32) COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录名',
  `password` varchar(100) COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码(BCrypt)',
  `nickname` varchar(32) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '姓名/昵称',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0禁用 1正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='后台管理员表';

-- 自检：本次建/确认出来的表结构（列数与列名必须与源库逐字相同）
SELECT `TABLE_SCHEMA` AS db, `TABLE_NAME` AS tb, `TABLE_ROWS` AS approx_rows, `TABLE_COMMENT` AS cmt
FROM `information_schema`.`TABLES`
WHERE `TABLE_SCHEMA` = 'mall_admin' AND `TABLE_NAME` = 'sys_user';

SELECT `ORDINAL_POSITION` AS pos, `COLUMN_NAME` AS col, `COLUMN_TYPE` AS typ,
       `IS_NULLABLE` AS nullable, `COLUMN_DEFAULT` AS dflt, `COLUMN_COMMENT` AS cmt
FROM `information_schema`.`COLUMNS`
WHERE `TABLE_SCHEMA` = 'mall_admin' AND `TABLE_NAME` = 'sys_user'
ORDER BY `ORDINAL_POSITION`;

-- 自检：与源库逐列比对（期望 0 行差异：列名/类型/可空/默认值/注释全同）
SELECT s.`ORDINAL_POSITION` AS pos, s.`COLUMN_NAME` AS col,
       s.`COLUMN_TYPE` AS src_type, t.`COLUMN_TYPE` AS dst_type,
       s.`IS_NULLABLE` AS src_nullable, t.`IS_NULLABLE` AS dst_nullable,
       s.`COLUMN_DEFAULT` AS src_dflt, t.`COLUMN_DEFAULT` AS dst_dflt
FROM `information_schema`.`COLUMNS` s
LEFT JOIN `information_schema`.`COLUMNS` t
       ON t.`TABLE_SCHEMA` = 'mall_admin' AND t.`TABLE_NAME` = s.`TABLE_NAME`
      AND t.`COLUMN_NAME` = s.`COLUMN_NAME`
WHERE s.`TABLE_SCHEMA` = 'mall' AND s.`TABLE_NAME` = 'sys_user'
  AND (t.`COLUMN_NAME` IS NULL
       OR t.`COLUMN_TYPE` <> s.`COLUMN_TYPE`
       OR t.`IS_NULLABLE` <> s.`IS_NULLABLE`
       OR NOT (t.`COLUMN_DEFAULT` <=> s.`COLUMN_DEFAULT`));

SELECT CASE WHEN (
         SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
         WHERE `TABLE_SCHEMA` = 'mall' AND `TABLE_NAME` = 'sys_user')
       = (
         SELECT COUNT(*) FROM `information_schema`.`COLUMNS`
         WHERE `TABLE_SCHEMA` = 'mall_admin' AND `TABLE_NAME` = 'sys_user')
       THEN 'SCHEMA-COLUMN-COUNT OK'
       ELSE 'SCHEMA-COLUMN-COUNT MISMATCH' END AS self_check;
