package com.mall.search.mq;

import com.mall.search.support.MqMessages;
import com.mall.search.support.MqTopology;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 商品索引同步消息的投递端。
 *
 * <p>三种投递路径：
 * <ol>
 *   <li>{@link #publishSpuIds} — 业务/管理端要同步商品时的正常路径；返回 false 表示没投出去
 *       （MQ 关闭或 broker 不可用），**由调用方决定兜底**：订单链路回落 Redis 队列、
 *       管理端链路直接同步执行</li>
 *   <li>{@link #publishRetry} — 消费失败后的延迟重投（TTL = {@code mall.mq.sync-retry-delay-ms}）</li>
 *   <li>{@link #publishRawRetry} / {@link #publishRawToDlq} — 连正文都解析不了时按原样重投/进死信</li>
 * </ol>
 *
 * <p>失败一律 fail-open（记 warn 不抛异常）：索引同步是"最终一致"的旁路，不该拖垮主链路；
 * 兜底是 Redis 队列 + 定时任务 + 全量重建（{@code POST /api/admin/es/product/reindex}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSyncPublisher {

    private final RabbitTemplate rabbitTemplate;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${mall.mq.sync-retry-delay-ms:10000}")
    private long retryDelayMs;

    /** 投递"按 spuId 同步"消息(自动按 {@link ProductSyncMessage#MAX_BATCH} 分批)；返回是否投递成功 */
    public boolean publishSpuIds(Collection<Long> spuIds) {
        if (!mqEnabled || spuIds == null || spuIds.isEmpty()) {
            return false;
        }
        List<Long> ids = normalize(spuIds);
        if (ids.isEmpty()) {
            return false;
        }
        boolean allOk = true;
        for (List<Long> batch : batches(ids)) {
            allOk &= sendFresh(ProductSyncMessage.ofSpuIds(batch));
        }
        return allOk;
    }

    /**
     * **业务链路的推荐入口**：事务提交后投递"按 spuId 同步"；MQ 关闭或投递失败时执行 {@code onFailure} 兜底。
     *
     * <p>为什么必须等提交后再发（本次全量回归实测踩到的坑）：
     * 订单/退款链路是在**事务内**调用 {@code markDirty} 的。如果消息立刻发出去，
     * 消费者可能先于事务提交去读库（读不到刚扣的库存），把**旧值**写进索引——
     * 表现是"索引里的库存比真实库存多"，而且**不报任何错**，非常难查。
     *
     * @param onFailure 兜底动作（订单链路=写 Redis 集合；管理端链路=直接同步执行）
     */
    public void publishSpuIds(Collection<Long> spuIds, Runnable onFailure) {
        List<Long> ids = normalize(spuIds);
        if (!mqEnabled || ids.isEmpty()) {
            // 即使走兜底也要等事务提交：兜底动作之一是"直接同步写 ES"（管理端链路），
            // 放在事务内会让 ES 的 2s/5s 超时压住行锁，还会产生"库里没提交、索引已可见"的脏文档
            afterCommitOrNow(onFailure);
            return;
        }
        afterCommitOrNow(() -> {
            boolean ok = true;
            for (List<Long> batch : batches(ids)) {
                ok &= sendFresh(ProductSyncMessage.ofSpuIds(batch));
            }
            if (!ok) {
                runQuietly(onFailure);
            }
        });
    }

    /** 投递"按品牌同步"消息；返回是否投递成功 */
    public boolean publishBrand(long brandId) {
        if (!mqEnabled) {
            return false;
        }
        return sendFresh(ProductSyncMessage.ofBrand(brandId));
    }

    /** 事务提交后投递"按品牌同步"；失败执行兜底 */
    public void publishBrand(long brandId, Runnable onFailure) {
        if (!mqEnabled || brandId <= 0) {
            afterCommitOrNow(onFailure);   // 同上：兜底(直接同步)也要等提交后
            return;
        }
        afterCommitOrNow(() -> {
            if (!sendFresh(ProductSyncMessage.ofBrand(brandId))) {
                runQuietly(onFailure);
            }
        });
    }

    /** 消费失败后的延迟重投（带上重试次数，到期回到工作队列） */
    public void publishRetry(ProductSyncMessage payload, int retry) {
        try {
            Message message = MqMessages.json(payload, (int) retryDelayMs, null,
                    java.util.Map.of(MqMessages.HEADER_RETRY, retry));
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_RETRY_ROUTING_KEY, message);
            log.warn("商品索引同步失败，第 {} 次重试将在 {}ms 后进行: spuIds={} brandId={}",
                    retry, retryDelayMs, payload.spuIds(), payload.brandId());
        } catch (Exception e) {
            log.error("商品索引同步重投失败(等全量重建筑底): {}", e.getMessage());
        }
    }

    /** 正文无法解析时的原样重投（保留原 body） */
    public void publishRawRetry(Message original, int retry, String reason) {
        try {
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_RETRY_ROUTING_KEY,
                    MqMessages.copyOf(original, retry, retryDelayMs, reason));
            log.warn("商品索引同步消息无法解析，第 {} 次重试将在 {}ms 后进行: {}", retry, retryDelayMs, reason);
        } catch (Exception e) {
            log.error("商品索引同步重投失败(等全量重建筑底): {}", e.getMessage());
        }
    }

    /** 重试次数超限 → 进死信队列（可观测，人工处理） */
    public void publishToDlq(ProductSyncMessage payload, int retry, String reason) {
        try {
            Message message = MqMessages.json(payload, null, null, java.util.Map.of(
                    MqMessages.HEADER_RETRY, retry, MqMessages.HEADER_FAIL_REASON, reason));
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_DLQ_ROUTING_KEY, message);
            log.error("商品索引同步进入死信队列(重试 {} 次仍失败): spuIds={} brandId={} 原因={}",
                    retry, payload.spuIds(), payload.brandId(), reason);
        } catch (Exception e) {
            log.error("商品索引同步进死信失败: {}", e.getMessage());
        }
    }

    /** 正文无法解析且重试超限 → 原样进死信队列 */
    public void publishRawToDlq(Message original, int retry, String reason) {
        try {
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_DLQ_ROUTING_KEY,
                    MqMessages.copyOf(original, retry, 0, reason));
            log.error("商品索引同步消息进入死信队列(无法解析且重试 {} 次): {}", retry, reason);
        } catch (Exception e) {
            log.error("商品索引同步进死信失败: {}", e.getMessage());
        }
    }

    private boolean sendFresh(ProductSyncMessage payload) {
        try {
            Message message = MqMessages.json(payload, null, null, null);
            rabbitTemplate.send(MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_ROUTING_KEY, message);
            log.debug("商品索引同步消息已投递: spuIds={} brandId={}", payload.spuIds(), payload.brandId());
            return true;
        } catch (Exception e) {
            log.warn("商品索引同步消息投递失败(将回落 Redis 队列/同步执行): {}", e.getMessage());
            return false;
        }
    }

    /** 在事务里 → 注册 afterCommit 回调；不在事务里 → 立刻执行 */
    private void afterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** 兜底动作本身也不能影响主链路 */
    private void runQuietly(Runnable onFailure) {
        if (onFailure == null) {
            return;
        }
        try {
            onFailure.run();
        } catch (Exception e) {
            log.error("索引同步兜底动作执行失败(等全量重建筑底): {}", e.getMessage());
        }
    }

    private List<Long> normalize(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();
        }
        return spuIds.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** 按 {@link ProductSyncMessage#MAX_BATCH} 分批，避免单条消息过大 */
    private List<List<Long>> batches(List<Long> ids) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += ProductSyncMessage.MAX_BATCH) {
            result.add(ids.subList(i, Math.min(i + ProductSyncMessage.MAX_BATCH, ids.size())));
        }
        return result;
    }
}
