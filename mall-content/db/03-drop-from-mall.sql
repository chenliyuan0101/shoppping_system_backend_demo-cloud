-- =====================================================================
-- 收尾：删除单体库里的 cms_* 表（P2 决策 2 的最后一步）
--
-- ⚠️ 只在**验证通过后**执行：
--   1) content 服务已能读写 mall_content 的这两张表（内部接口真库用例通过）；
--   2) 单体的 cms mapper/entity/service 已删除，只剩薄转发（不再直连这两张表）；
--   3) 网关已把 /api/home/**、/api/banner|notice/list、/api/admin/banner|notice/** 切到 content。
-- 回退方式：此时"回退"只能回退**代码提交**（git revert），不能再靠改网关路由——
-- 因为数据已经在 mall_content 里。这是 P2 之后所有"数据先走"阶段的共同性质。
-- =====================================================================

DROP TABLE IF EXISTS `mall`.`cms_banner`;
DROP TABLE IF EXISTS `mall`.`cms_notice`;
