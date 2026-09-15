-- =====================================================================
-- ⚠️ 历史留档：本文件是"建库 / 迁数据时期"的手工脚本，**已不再执行**。
--   · 结构权威 = `src/main/resources/db/migration/V1__baseline.sql`（v5.3 起纳入 Flyway，mysqldump 导出）
--   · 之后的任何结构变更一律**新增** `V2__xxx.sql`，不要改 V1、也不要再手跑本文件
--     （否则 mall_user 的结构会有两个来源，Flyway 校验也会失配）
--   · 保留本文件的唯一用途：查"当年这一步是怎么做的"（数据迁移的 SQL 仍有参考价值）
-- =====================================================================
-- =====================================================================
-- 收尾：删除单体库里的 ums_* 表（P3-4 的最后一步，**只在验收后执行**）
--
-- ⚠️ 前置条件（缺一不可）：
--   1) user-center 已能读写 mall_user 的 6 张表，且其真库用例通过；
--   2) 单体已删除 auth/ums 的 domain/mapper/service（不再直连这些表）；
--   3) oms/admin/pms/sms 对会员/地址/购物车的访问**全部**改为调用 user-center 的
--      /internal/v1/user/**（MemberQueryService / MemberAdminService /
--      CartCheckoutService / AddressQueryService 的调用方都已客户端化）；
--   4) 网关已把 /api/auth/**、/api/address|cart|favorite|footprint|notification/**
--      切到 mall-user-center，且全站 401 场景回归通过。
--
-- 回退方式：此时只能回退**代码**（数据已在 mall_user），与 P2 拆 cms_* 时同理。
-- =====================================================================

DROP TABLE IF EXISTS `mall`.`ums_member`;
DROP TABLE IF EXISTS `mall`.`ums_address`;
DROP TABLE IF EXISTS `mall`.`ums_cart_item`;
DROP TABLE IF EXISTS `mall`.`ums_favorite`;
DROP TABLE IF EXISTS `mall`.`ums_footprint`;
-- P3-5 状态：代码侧的搬迁**已完成**——通知的消费者是本服务的
-- NotificationEventConsumer（自己的队列 mall.user.notification），单体 oms 的
-- OrderEventConsumer 只剩"重算订单日统计"，单体已无任何写 mall.ums_notification 的代码路径。
-- 因此下面这行可以执行了；保留注释只是为了让"表还在、行不再增长"这个观察期显式过一遍
-- （观察期内若两边都有新行，说明还有第二个写入方没找出来）。
-- DROP TABLE IF EXISTS `mall`.`ums_notification`;
