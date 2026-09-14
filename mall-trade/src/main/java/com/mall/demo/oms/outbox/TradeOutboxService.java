package com.mall.demo.oms.outbox;

import com.mall.demo.common.JsonKit;
import com.mall.demo.oms.domain.TradeOutbox;
import com.mall.demo.oms.mapper.TradeOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 发件箱的**入库与状态流转**（P8-3）。投递本身在 {@link TradeOutboxRelay}。
 *
 * <h2>为什么入库必须发生在业务事务里</h2>
 * {@link #enqueue} 只做一件事：`INSERT`。它<b>不</b>注册 afterCommit、<b>不</b>碰 MQ ——
 * 于是它天然跟随调用方的事务：业务回滚 ⇒ 发件箱行也回滚（不会发出"业务没成"的事件）；
 * 业务提交 ⇒ 行一定在库里（进程立刻崩掉也不会丢）。
 *
 * <p>这条性质还顺手抵消了一个历史坑（见 P7 的 `TxCallbacks` 记录）：**嵌套 afterCommit 不会触发**。
 * 在发件箱方案里，即使"提交后立即投递"的回调因为嵌套而没被调用，**行已经在库里**，
 * 定时重投会把它发出去 ⇒ 那条历史缺陷在投递可靠性上**变成无害**（只是晚几秒）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeOutboxService {

    private final TradeOutboxMapper mapper;

    /** 单次重投批量上限 */
    @Value("${mall.mq.outbox.relay-batch:50}")
    private int batch;

    /** 重试次数上限（超过 ⇒ status=2 已放弃） */
    @Value("${mall.mq.outbox.max-retry:12}")
    private int maxRetry;

    /** 退避基数（秒）：第 n 次失败后等 n×base 秒，封顶 300 秒 */
    @Value("${mall.mq.outbox.backoff-seconds:5}")
    private int backoffSeconds;

    /**
     * 入箱（**必须在业务事务内调用**）。payload 用与直投路径**同一个** {@code JsonKit}
     * 序列化 ⇒ 存下来的正文与原先直接发出去的是同一串字节。
     *
     * @param eventType  事件类型（运维视角的分类，如 order.paid）
     * @param routingKey MQ 路由键（与消费方拓扑逐字一致）
     * @param bizKey     业务键（订单号等），同时用作消息 messageId
     * @param payload    载荷对象
     * @return 入箱行（含自增 id，便于"提交后立刻投递"这条快路径直接用）
     */
    public TradeOutbox enqueue(String eventType, String routingKey, String bizKey, Object payload) {
        TradeOutbox row = new TradeOutbox();
        row.setEventType(eventType);
        row.setRoutingKey(routingKey);
        row.setBizKey(bizKey);
        row.setPayload(JsonKit.toJson(payload));
        row.setStatus(TradeOutbox.STATUS_PENDING);
        row.setRetryCount(0);
        mapper.insert(row);
        return row;
    }

    public TradeOutbox findById(Long id) {
        return mapper.selectById(id);
    }

    /** 待发送且已到期的一批（按 id 升序） */
    public List<TradeOutbox> findDue() {
        return mapper.findDue(batch);
    }

    /** 标记已发送；返回 false 表示"已经被别人标记过/已被放弃"（HTTP 之外的并发保护） */
    public boolean markSent(Long id) {
        return mapper.markSent(id) == 1;
    }

    /** 标记失败（重试 +1；超限直接进已放弃） */
    public void markFailed(Long id, String error) {
        mapper.markFailed(id, truncate(error), maxRetry, backoffSeconds);
    }

    /** 悬挂条数：入箱超过 N 秒仍未发出 */
    public int countHanging(int seconds) {
        return mapper.countHanging(seconds);
    }

    public int countAbandoned() {
        return mapper.countAbandoned();
    }

    public List<TradeOutbox> findByBizKey(String bizKey) {
        return mapper.findByBizKey(bizKey);
    }

    /**
     * 归档已发送的老行（P8-4）：只删 {@code status=1 且 sent_at < now - days} 的行，
     * 单次最多 {@code limit} 行（见 Mapper 注释里的三条边界）。
     *
     * @return 本次删掉的行数
     */
    public int archiveSent(int days, int limit) {
        return mapper.archiveSent(days, limit);
    }

    /** last_error 是 varchar(500)，超长会被 MySQL 截断并告警 ⇒ 先自己截 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
