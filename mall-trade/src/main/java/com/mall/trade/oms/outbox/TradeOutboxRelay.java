package com.mall.trade.oms.outbox;

import com.mall.trade.common.MqMessages;
import com.mall.trade.common.MqTopology;
import com.mall.trade.oms.domain.TradeOutbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发件箱投递器（P8-3）：把 {@code trade_outbox} 里"待发送"的行发到 RabbitMQ，成功后标记已发送。
 *
 * <h2>两条投递路径（同一条"发送 + 标记"逻辑）</h2>
 * <ol>
 *   <li><b>快路径</b>（{@link #sendOne(Long)}，由 {@code OrderEventPublisher} 在**业务事务提交后**调用）：
 *       保持与 P8 之前**一样的时延**（事件仍是提交后立刻发出），只是多发了一次"标记已发送"的更新。</li>
 *   <li><b>定时重投</b>（{@link #relay()}）：扫"待发送且到期"的行。**进程崩溃/发送失败的行就是靠它补发的** ——
 *       重启后这些行的 {@code next_retry_at} 是 NULL 或已过期，第一轮扫描就会把它们发出去。</li>
 * </ol>
 *
 * <h2>可靠性口径（别把它想得比实际更强）</h2>
 * <ul>
 *   <li><b>至少一次</b>：若进程在"MQ 已接收"与"标记已发送"之间崩溃，重启后会**再发一次**（重复投递）。
 *       消费侧必须幂等——这是 P8 规格接受的取舍（重复可由幂等消化，丢事件不可恢复）。</li>
 *   <li><b>不是"事务性消息"</b>：没有用 RabbitMQ 的 publisher confirm / tx channel。理由是本项目的消费侧
 *       要么重算幂等（按日统计）、要么按业务键可查（通知/券），并且不引入同步等待发布确认带来的 RT 抖动。</li>
 *   <li><b>发不出去不影响业务</b>：与改造前一致 —— 投递失败只记日志、把行留在箱里等重投，
 *       绝不把异常抛回业务（支付/发货/退款不能因为 MQ 抖动而失败）。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeOutboxRelay {

    private final TradeOutboxService outbox;
    private final RabbitTemplate rabbitTemplate;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    /** 总开关：关掉只影响"投递"，入库仍然发生（用于演练与排障） */
    @Value("${mall.mq.outbox.enabled:true}")
    private boolean outboxEnabled;

    /**
     * 快路径开关。**默认开**（保持改造前的时延）。
     * 关掉时事件仍然入箱，但要等定时重投才会发出去 —— 这正是 P8-3 的"杀进程补发"演练要的姿态：
     * `--mall.mq.outbox.fast-path=false` 启动 ⇒ 入库不发送 ⇒ 杀进程 ⇒ 用默认参数重启 ⇒ 定时重投补发。
     */
    @Value("${mall.mq.outbox.fast-path:true}")
    private boolean fastPath;

    /** 定时重投开关（演练时也可单独关，观察"只入库不发送"） */
    @Value("${mall.mq.outbox.relay-enabled:true}")
    private boolean relayEnabled;

    /** 悬挂判定阈值（秒）：入箱超过这么久仍未发出 ⇒ 对账告警 */
    @Value("${mall.mq.outbox.hang-threshold-seconds:300}")
    private int hangThresholdSeconds;

    /** 归档保留天数：已发送行的保留期（P8-4 新增，见 {@link #archive()}） */
    @Value("${mall.mq.outbox.retention-days:7}")
    private int retentionDays;

    /** 归档单轮上限 */
    @Value("${mall.mq.outbox.archive-batch:1000}")
    private int archiveBatch;

    /** 快路径是否启用（给 Publisher 判断用；入库本身不受它影响） */
    public boolean fastPathEnabled() {
        return mqEnabled && outboxEnabled && fastPath;
    }

    /** 定时重投：扫"待发送且到期"的行，逐条发（按 id 升序 ⇒ 同一业务的事件不乱序） */
    @Scheduled(fixedDelayString = "${mall.mq.outbox.relay-interval-ms:2000}",
            initialDelayString = "${mall.mq.outbox.relay-initial-delay-ms:8000}")
    public void relay() {
        if (!mqEnabled || !outboxEnabled || !relayEnabled) {
            return;
        }
        List<TradeOutbox> due = outbox.findDue();
        if (due.isEmpty()) {
            return;
        }
        int sent = 0;
        for (TradeOutbox row : due) {
            if (sendOne(row.getId())) {
                sent++;
            }
        }
        log.info("发件箱重投一轮: 待发={} 成功={}", due.size(), sent);
    }

    /**
     * 发送并标记单行。返回是否"本次真的发出去了"（false = 没发出 / 已被别人处理 / 还没到重投时间）。
     *
     * <p>幂等：只有当行仍是"待发送(0)"时才发送与标记；{@code markSent} 再用 {@code status = 0} 作条件，
     * 于是并发/重复调用不会把同一行标记两次、也不会重复计入 sent。
     */
    public boolean sendOne(Long id) {
        if (!mqEnabled || !outboxEnabled) {
            return false;
        }
        TradeOutbox row = outbox.findById(id);
        if (row == null || row.getStatus() == null || row.getStatus() != TradeOutbox.STATUS_PENDING) {
            return false;
        }
        try {
            rabbitTemplate.send(MqTopology.EVENT_EXCHANGE, row.getRoutingKey(),
                    MqMessages.raw(row.getPayload(), row.getBizKey()));
            boolean marked = outbox.markSent(id);
            if (marked) {
                log.info("发件箱已投递: id={} type={} routingKey={} bizKey={} retry={}",
                        id, row.getEventType(), row.getRoutingKey(), row.getBizKey(), row.getRetryCount());
            }
            return marked;
        } catch (Exception e) {
            outbox.markFailed(id, e.getMessage());
            log.warn("发件箱投递失败(不影响业务，等重投): id={} type={} routingKey={} bizKey={} 原因={}",
                    id, row.getEventType(), row.getRoutingKey(), row.getBizKey(), e.getMessage());
            return false;
        }
    }

    /**
     * 每日悬挂对账（P8-spec §P8-3 的第三条验收）：入箱超过阈值仍未发出的行 + 已放弃的行。
     *
     * <p>正常运行时两者都是 0；不为 0 就打 ERROR（**这就是"事件丢了没人知道"的兜底**：
     * 至少在日志里被人看见），并逐条打印 id/类型/业务键/重试次数，便于人工重投。
     */
    @Scheduled(cron = "${mall.mq.outbox.reconcile-cron:0 10 3 * * ?}")
    public void reconcile() {
        if (!mqEnabled || !outboxEnabled) {
            return;
        }
        int hanging = outbox.countHanging(hangThresholdSeconds);
        int abandoned = outbox.countAbandoned();
        if (hanging == 0 && abandoned == 0) {
            log.info("发件箱悬挂对账: 无异常（阈值={}s）", hangThresholdSeconds);
            return;
        }
        log.error("发件箱悬挂对账: **有事件没发出去** 超时未发={} 已放弃={}（阈值={}s）—— 需要人工处理；"
                        + "明细：SELECT id,event_type,routing_key,biz_key,retry_count,created_at,last_error "
                        + "FROM trade_outbox WHERE status IN (0,2) ORDER BY id",
                hanging, abandoned, hangThresholdSeconds);
    }

    /**
     * 归档已发送的老行（P8-4）：发件箱是**只增**的表，不清理会无限增长（P8-3 报告里记账的遗留项）。
     *
     * <p>口径：只删"**已发送**且发送时间超过 {@code retention-days} 天"的行；待发送/已放弃一律不碰
     * （它们是没了结的事，归对账处理）。默认每天 03:30 跑一轮，单轮最多 {@code archive-batch} 行。
     */
    @Scheduled(cron = "${mall.mq.outbox.archive-cron:0 30 3 * * ?}")
    public void archive() {
        if (!outboxEnabled) {
            return;
        }
        int deleted = outbox.archiveSent(retentionDays, archiveBatch);
        if (deleted > 0) {
            log.info("发件箱归档: 删除已发送且超过 {} 天的行 {} 条", retentionDays, deleted);
        } else {
            log.debug("发件箱归档: 无需删除（保留 {} 天）", retentionDays);
        }
    }
}
