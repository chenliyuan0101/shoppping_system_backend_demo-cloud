-- ⚠️ P8-4 起：**权威结构脚本已迁到 Flyway** —— `src/main/resources/db/migration/V1__baseline.sql`
--    本文件降级为**历史手工脚本**（P8-1/P8-3 期的一次性产物，留档用）。
--    **不要再手跑它**：否则"结构"会有两个来源，Flyway 的 `flyway_schema_history` 与实际库不一致。
--    （应用启动时 Flyway 会自动 migrate/baseline，不需要人工执行任何 SQL。）
-- =====================================================================
-- P8-3：`trade_outbox`（发件箱）—— 领域事件先落库、再投递，进程崩了也不丢
-- 背景（现状缺陷，P8 规格 §P8-3 原文）：现在是"**事务提交后直接投 MQ**"（`publishRawAfterCommit`），
--   **进程在投递前崩掉 ⇒ 事件永久丢失**（消费侧幂等做得再好也救不回来，因为消息根本没发出去）。
-- 目标语义：**至少一次**投递 + 可重放；由"定时重投未发送的" + "每日悬挂对账（超时未发告警）"兜底。
-- ⚠️ 本文件只建表；**代码接线（发布路径改为 同事务落库 → 提交后投递 → 标记已发）属于 P8-2 之后**，
--   因为它要改的是正在运行的单体（P8-2 会把单体更名 mall-trade 并搬到本目录）⇒ 必须与那次窗口同批做。
-- =====================================================================
USE mall_trade;

CREATE TABLE IF NOT EXISTS `trade_outbox` (
  `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键（也用作投递顺序：按 id 升序发）',
  `event_type`    varchar(64)  NOT NULL COMMENT '事件类型（如 order.paid / order.closed / order.finished）',
  `routing_key`   varchar(64)  NOT NULL COMMENT 'MQ 路由键（与消费方声明的拓扑逐字一致）',
  `payload`       mediumtext   NOT NULL COMMENT '消息正文（JSON，与现状逐字一致——消费方按它反序列化）',
  `biz_key`       varchar(64)  DEFAULT NULL COMMENT '业务键（订单号等），便于排查与"同键去重"',
  `status`        tinyint      NOT NULL DEFAULT 0 COMMENT '0=待发送 1=已发送 2=已放弃(进 DLQ 或人工)',
  `retry_count`   int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
  `next_retry_at` datetime     DEFAULT NULL COMMENT '下次可重投时间（退避；NULL=立即可发）',
  `last_error`    varchar(500) DEFAULT NULL COMMENT '最后一次失败原因（运维可见；不含敏感信息）',
  `created_at`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入箱时间（=业务事务提交时间）',
  `sent_at`       datetime     DEFAULT NULL COMMENT '投递成功时间',
  PRIMARY KEY (`id`),
  KEY `idx_status_next` (`status`, `next_retry_at`) COMMENT '定时重投扫描用：只扫待发送且到期的',
  KEY `idx_biz_key` (`biz_key`),
  KEY `idx_created` (`created_at`) COMMENT '悬挂对账用：找"入箱很久仍未发"的'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='交易域事件发件箱（至少一次投递）';

-- 自检
SELECT 'trade_outbox 已就绪' AS check_block;
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='mall_trade' AND TABLE_NAME='trade_outbox' ORDER BY ORDINAL_POSITION;