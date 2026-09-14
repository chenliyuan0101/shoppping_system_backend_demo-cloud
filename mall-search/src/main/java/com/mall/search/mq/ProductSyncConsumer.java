package com.mall.search.mq;

import com.mall.search.support.MqMessages;
import com.mall.search.support.MqTopology;
import com.mall.search.service.ProductSearchService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 商品索引同步消费者：把"要同步的 spuId / 品牌"落到 Elasticsearch。
 *
 * <p>处理语义（{@code acknowledge-mode: manual}）：
 * <ul>
 *   <li>全部成功 → {@code basicAck}</li>
 *   <li>部分/全部失败 → 把**失败的 id** 投到重试队列（TTL 到期后自动回到本队列），
 *       重试次数超过 {@code mall.mq.sync-max-retry} 后进死信队列(mall.pms.es-sync.dlq)，然后 ack 原消息</li>
 *   <li>正文解析失败(毒消息) → 原样重投同样次数，超限后进死信</li>
 * </ul>
 *
 * <p>为什么不用 {@code nack(requeue=true)} 做重试：会立即回到队首、把消费者打成死循环，
 * 且没有退避间隔。用"重试队列 + TTL"既能退避，又能限制次数与留下死信证据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class ProductSyncConsumer {

    private final ProductSearchService productSearchService;
    private final ProductSyncPublisher productSyncPublisher;

    @Value("${mall.mq.sync-max-retry:3}")
    private int maxRetry;

    @RabbitListener(queues = MqTopology.SYNC_QUEUE)
    public void onProductSync(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        int retry = MqMessages.retryCount(message);

        ProductSyncMessage payload;
        try {
            payload = MqMessages.payload(message, ProductSyncMessage.class);
        } catch (Exception e) {
            // 连正文都解析不了：按原样重投，超限进死信
            retryOrDlqRaw(message, channel, deliveryTag, retry, "消息解析失败: " + e.getMessage());
            return;
        }

        try {
            if (payload.isBrand()) {
                int synced = productSearchService.syncByBrand(payload.brandId());
                log.info("品牌索引同步完成: brandId={} 同步 {} 个在架商品", payload.brandId(), synced);
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 🆕 P6-5 批量路径收口：**整批一次** syncProducts（一次取内容 + 一次 bulk + 恰好一次 refresh），
            //    不再 for 循环逐条 syncProduct（那会让 N 篇 = N 次写 + N 次 refresh）。
            //    失败列表的语义与原循环**逐条一致**：由 syncProducts 按 ES bulk 的 per-item 结果映射回来，
            //    保序、保留重复、null 入参也照旧算一条失败（随后被下面的 filter 过滤掉、不再重投）。
            List<Long> failed = productSearchService.syncProducts(payload.spuIds());
            if (failed.isEmpty()) {
                log.debug("商品索引同步完成: {} 个", payload.spuIds().size());
                channel.basicAck(deliveryTag, false);
                return;
            }

            List<Long> retryIds = failed.stream().filter(Objects::nonNull).toList();
            if (retry + 1 <= maxRetry) {
                productSyncPublisher.publishRetry(ProductSyncMessage.ofSpuIds(retryIds), retry + 1);
            } else {
                productSyncPublisher.publishToDlq(ProductSyncMessage.ofSpuIds(retryIds), retry + 1,
                        "同步失败次数超过上限 " + maxRetry);
            }
            channel.basicAck(deliveryTag, false);   // 原消息已"处理"（转投重试/死信），ack 掉避免堆积
        } catch (Exception e) {
            // 业务异常(DB 抖动等)：同样转重试/死信，不用 requeue 死循环
            log.error("商品索引同步处理异常: spuIds={} brandId={} 原因={}", payload.spuIds(), payload.brandId(),
                    e.getMessage(), e);
            if (retry + 1 <= maxRetry) {
                productSyncPublisher.publishRetry(payload, retry + 1);
            } else {
                productSyncPublisher.publishToDlq(payload, retry + 1, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            channel.basicAck(deliveryTag, false);
        }
    }

    private void retryOrDlqRaw(Message message, Channel channel, long deliveryTag, int retry, String reason)
            throws IOException {
        if (retry + 1 <= maxRetry) {
            productSyncPublisher.publishRawRetry(message, retry + 1, reason);
        } else {
            productSyncPublisher.publishRawToDlq(message, retry + 1, reason);
        }
        channel.basicAck(deliveryTag, false);
    }
}
