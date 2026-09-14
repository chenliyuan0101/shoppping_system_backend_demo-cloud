package com.mall.search.config;

import com.mall.search.support.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 商品索引同步拓扑的**装配**（本服务版）。
 *
 * <p>⚠️ <b>声明参数与单体逐个一致，一个字都不许改</b>（规格 §1.2/§3）：
 * <pre>
 *   交换机   mall.pms.sync            DirectExchange, durable=true
 *   工作队列 mall.pms.es-sync        durable, DLX=mall.pms.sync, DLK=product.sync.dlq
 *   重试队列 mall.pms.es-sync.retry  durable, DLX=mall.pms.sync, DLK=product.sync
 *                                    （**队列级不设 x-message-ttl**：TTL 由每条消息自带）
 *   死信队列 mall.pms.es-sync.dlq    durable（无消费者，仅观测/人工处理）
 *   绑定     product.sync / product.sync.retry / product.sync.dlq
 * </pre>
 * 为什么这么较真：队列/交换机的**声明参数是 broker 上的既有定义**，
 * 参数不一致时 broker 会直接拒绝声明（{@code PRECONDITION_FAILED}，P5 踩过）。
 * 过渡期单体也还在声明同一套拓扑（它还要发消息），两边必须完全一样。
 *
 * <p>⚠️ {@code @ConditionalOnProperty(mall.mq.enabled, matchIfMissing = true)} **与单体逐字一致**：
 * 默认**装配**（本服务的存在意义之一就是消费这条队列）。
 * 置 false ⇒ 拓扑与消费者都不装配 ⇒ 本服务完全不连 broker（没有 broker 的机器也能跑用例）。
 * 这与 mall-marketing 的默认值（false）**不同**，是刻意的：marketing 的 MQ 只是"第二道防线"，
 * 而检索同步**就是本服务的主职**——默认关掉等于默认不干活。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public DirectExchange productSyncExchange() {
        return ExchangeBuilder.directExchange(MqTopology.SYNC_EXCHANGE).durable(true).build();
    }

    /** 同步工作队列：消费失败(nack 不重回队列) → 死信队列 */
    @Bean
    public Queue productSyncQueue() {
        return QueueBuilder.durable(MqTopology.SYNC_QUEUE)
                .deadLetterExchange(MqTopology.SYNC_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.SYNC_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding productSyncBinding(Queue productSyncQueue, DirectExchange productSyncExchange) {
        return BindingBuilder.bind(productSyncQueue).to(productSyncExchange).with(MqTopology.SYNC_ROUTING_KEY);
    }

    /**
     * 重试队列：没有消费者，消息按**每条消息自己的 TTL** 在这里等待，到期后死信回工作队列。
     *
     * <p>刻意不用队列级 {@code x-message-ttl}：队列参数一旦声明就不能改，
     * 会把"重试间隔"锁死在队列定义上（测试想压到几百毫秒就得重建队列）。
     */
    @Bean
    public Queue productSyncRetryQueue() {
        return QueueBuilder.durable(MqTopology.SYNC_RETRY_QUEUE)
                .deadLetterExchange(MqTopology.SYNC_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.SYNC_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding productSyncRetryBinding(Queue productSyncRetryQueue, DirectExchange productSyncExchange) {
        return BindingBuilder.bind(productSyncRetryQueue).to(productSyncExchange)
                .with(MqTopology.SYNC_RETRY_ROUTING_KEY);
    }

    @Bean
    public Queue productSyncDlq() {
        return QueueBuilder.durable(MqTopology.SYNC_DLQ).build();
    }

    @Bean
    public Binding productSyncDlqBinding(Queue productSyncDlq, DirectExchange productSyncExchange) {
        return BindingBuilder.bind(productSyncDlq).to(productSyncExchange).with(MqTopology.SYNC_DLQ_ROUTING_KEY);
    }
}
