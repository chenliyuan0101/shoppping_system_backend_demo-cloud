-- =====================================================================
-- mall_admin 的 Flyway 基线 V1：**现有结构**（P8-4 建库（sys_user 从 mall 迁入） 之后的真实状态）
--
-- ⚠️ 本文件是 **mysqldump 导出的真实结构**，不是手抄：
--    mysqldump --user=root --host=127.0.0.1 --no-data --compact --skip-add-drop-table `
--              --skip-comments --set-gtid-purged=OFF mall_admin
-- ⚠️ 已有库首次接入 Flyway 用 baseline-on-migrate=true（baseline-version=1）：
--    库里已有这些表 ⇒ V1 **不会被执行**，只在 flyway_schema_history 里记一条基线；
--    空库（全新环境）⇒ V1 会真的执行，把结构建出来（同一份脚本两种用法都对）。
-- ⚠️ 与本目录外的 db/0x-*.sql 的关系：**权威 = 本文件**。
--    db/ 下那几份是建库/迁数据时期的历史手工脚本，只作留档：别再手跑，否则结构会有两个来源。
-- ⚠️ **之后的任何结构变更一律新增 V2__xxx.sql，不要修改本文件**（改了会让已接入的库校验失败）。
-- =====================================================================
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '登录名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '密码(BCrypt)',
  `nickname` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '' COMMENT '姓名/昵称',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态 0禁用 1正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB AUTO_INCREMENT=1036 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='后台管理员表';
/*!40101 SET character_set_client = @saved_cs_client */;
