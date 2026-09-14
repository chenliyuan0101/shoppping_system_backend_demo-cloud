package com.mall.usercenter.config;

import com.mall.usercenter.support.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 站内消息链路的 RabbitMQ 拓扑<b>装配</b>（P3-5：通知的写入方从单体搬到本服务）。
 *
 * <p>名字（交换机/队列/路由键）来自共享契约副本 {@link MqTopology}；本类只负责"把契约声明成基础设施"——
 * 这是每个服务自带组装根的规矩（见《微服务改造方案.md》§6.1）。
 *
 * <p><b>只声明自己用到的那一半</b>：单体那套"订单超时关单 / 商品索引同步"的拓扑与本服务无关，
 * 一行都不声明；本服务也不声明 {@code mall.oms.events}（单体的事件队列）——那样会变成竞争消费。
 *
 * <h2>声明参数的坑（实测会炸的地方）</h2>
 * {@link MqTopology#EVENT_EXCHANGE} 是<b>两个服务各自声明</b>的共享交换机：RabbitMQ 以第一次声明的
 * 参数为准，后到者不一致直接 {@code PRECONDITION_FAILED} 并让监听容器起不来。因此这里是
 * {@code ExchangeBuilder.topicExchange(EVENT_EXCHANGE).durable(true)}，与单体 {@code RabbitMqConfig}
 * 一字不差（topic + durable + 无 arguments）。队列名是新的，参数只有"自己的死信指向"。
 *
 * <h2>失败链路：与单体同形，但键名各自独立</h2>
 * <pre>
 * 通知工作队列 mall.user.notification ──失败(重试超限/正文解析失败)──► 死信 mall.user.notification.dlq
 *        ▲                                                                  (绑定键 notification.dlq)
 *        │ 绑定键 notification.redeliver
 * 重试队列 mall.user.notification.retry ──消息自身 TTL 到期──┘   (绑定键 notification.retry)
 * </pre>
 * 三个键刻意不叫 {@code event.retry/redeliver/dlq}：那两个名字属于单体的队列，共用会让双方死信互相串味
 * （详见 {@link MqTopology#NOTIFICATION_ROUTING_RETRY} 的注释）。
 *
 * <p>⚠️ {@code mall.mq.enabled=false} 时本类不装配：本服务完全不连接 RabbitMQ（本地/CI 没有 broker
 * 也能跑全部用例；MQ 链路本身由 {@code NotificationEventMqMySqlTest} 单独打开开关验证）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    // ==================== 共享交换机（与单体声明参数必须一致） ====================

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(MqTopology.EVENT_EXCHANGE, true, false);
    }

    // ==================== 通知工作队列（本服务自己的队列） ====================

    /** 通知消费队列：消费失败 / 重试超限 → 死信交换机(同一个共享交换机) + 键 notification.dlq */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(MqTopology.NOTIFICATION_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.NOTIFICATION_ROUTING_DLQ)
                .build();
    }

    @Bean
    public Binding notificationPaidBinding(Queue notificationQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_PAID);
    }

    @Bean
    public Binding notificationShippedBinding(Queue notificationQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_SHIPPED);
    }

    @Bean
    public Binding notificationRefundBinding(Queue notificationQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_REFUND);
    }

    /** 重试归来：只绑工作队列，避免与重试队列互相"抢"同一条消息造成死循环 */
    @Bean
    public Binding notificationRedeliverBinding(Queue notificationQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationQueue).to(orderEventExchange)
                .with(MqTopology.NOTIFICATION_ROUTING_REDELIVER);
    }

    // ==================== 通知重试 / 死信队列 ====================

    /**
     * 重试队列：没有消费者，消息按**每条消息自己的 TTL** 在这里等待，到期后死信回工作队列。
     *
     * <p>与单体同样的取舍：不用队列级 {@code x-message-ttl}——队列参数一旦声明就不能改，
     * 会把"重试间隔"锁死在队列定义上（测试想压到 200ms 就得重建队列）；每条消息带 TTL
     * 则完全由 {@code mall.mq.event-retry-delay-ms} 决定。
     */
    @Bean
    public Queue notificationRetryQueue() {
        return QueueBuilder.durable(MqTopology.NOTIFICATION_RETRY_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.NOTIFICATION_ROUTING_REDELIVER)
                .build();
    }

    @Bean
    public Binding notificationRetryBinding(Queue notificationRetryQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationRetryQueue).to(orderEventExchange)
                .with(MqTopology.NOTIFICATION_ROUTING_RETRY);
    }

    /** 死信队列：无消费者，仅用于观测与人工重投（毒消息不会无限重入工作队列） */
    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(MqTopology.NOTIFICATION_DLQ).build();
    }

    @Bean
    public Binding notificationDlqBinding(Queue notificationDlq, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(notificationDlq).to(orderEventExchange)
                .with(MqTopology.NOTIFICATION_ROUTING_DLQ);
    }
}
