package com.mall.review.config;

import com.mall.review.support.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 待评价读模型链路的 RabbitMQ 拓扑<b>装配</b>（P4-2：order.finished → review_pending_item）。
 *
 * <p>名字（交换机/队列/路由键）来自共享契约副本 {@link MqTopology}；本类只负责"把契约声明成基础设施"——
 * 这是每个服务自带组装根的规矩（见《微服务改造方案.md》§6.1）。
 *
 * <p><b>只声明自己用到的那一半</b>：单体那套"订单超时关单 / 商品索引同步 / 事件工作队列"
 * 以及 user-center 的通知队列与本服务无关，一行都不声明；本服务也<b>不</b>声明
 * {@code mall.oms.events} / {@code mall.user.notification}——那样会变成竞争消费。
 * 本服务只关心一个业务键 {@code order.finished}（评价域对 paid/shipped/refund 没有副作用），
 * 因此工作队列上只有一条业务绑定。
 *
 * <h2>声明参数的坑（实测会炸的地方）</h2>
 * {@link MqTopology#EVENT_EXCHANGE} 是<b>多个服务各自声明</b>的共享交换机：RabbitMQ 以第一次声明的
 * 参数为准，后到者不一致直接 {@code PRECONDITION_FAILED} 并让监听容器起不来。实测（P4-2 施工时经
 * 管理 API 读取）该交换机当前是 {@code topic / durable=true / auto_delete=false / internal=false /
 * arguments={}}，因此这里是 {@code new TopicExchange(EVENT_EXCHANGE, true, false)}，与单体
 * {@code app.RabbitMqConfig}、user-center 的 {@code RabbitMqConfig} 一字不差
 * （topic + durable + 非 autoDelete + 无 arguments）。队列名是新的，参数只有"自己的死信指向"。
 *
 * <h2>失败链路：与单体/user-center 同形，但键名各自独立</h2>
 * <pre>
 * 工作队列 mall.review.order-finished ──失败(重试超限/正文解析失败)──► 死信 mall.review.order-finished.dlq
 *        ▲                                                                        (绑定键 review.dlq)
 *        │ 绑定键 review.redeliver
 * 重试队列 mall.review.order-finished.retry ──消息自身 TTL 到期──┘   (绑定键 review.retry)
 * </pre>
 * 三个键刻意不叫 {@code event.*}（单体的）也不叫 {@code notification.*}（user-center 的）：
 * 共用会让双方死信互相串味（详见 {@link MqTopology#REVIEW_ROUTING_RETRY} 的注释）。
 *
 * <p>⚠️ {@code mall.mq.enabled=false} 时本类不装配：本服务完全不连接 RabbitMQ（本地/CI 没有 broker
 * 也能跑全部用例；MQ 链路本身由 {@code OrderFinishedMqMySqlTest} 单独打开开关验证）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    // ==================== 共享交换机（与单体声明参数必须一致） ====================

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(MqTopology.EVENT_EXCHANGE, true, false);
    }

    // ==================== 待评价投影工作队列（本服务自己的队列） ====================

    /** 投影消费队列：消费失败 / 重试超限 → 死信交换机(同一个共享交换机) + 键 review.dlq */
    @Bean
    public Queue reviewOrderFinishedQueue() {
        return QueueBuilder.durable(MqTopology.REVIEW_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.REVIEW_ROUTING_DLQ)
                .build();
    }

    /**
     * 唯一的业务绑定：{@code order.finished} → 本服务的队列。
     *
     * <p>这条绑定就是"扇出"的全部实现——交换机上多一条边，单体的 {@code mall.oms.events} 与
     * user-center 的 {@code mall.user.notification} 一条未动，也不会有谁少收消息。
     */
    @Bean
    public Binding reviewOrderFinishedBinding(Queue reviewOrderFinishedQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(reviewOrderFinishedQueue).to(orderEventExchange)
                .with(MqTopology.EVENT_ROUTING_FINISHED);
    }

    /** 重试归来：只绑工作队列，避免与重试队列互相"抢"同一条消息造成死循环 */
    @Bean
    public Binding reviewRedeliverBinding(Queue reviewOrderFinishedQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(reviewOrderFinishedQueue).to(orderEventExchange)
                .with(MqTopology.REVIEW_ROUTING_REDELIVER);
    }

    // ==================== 投影重试 / 死信队列 ====================

    /**
     * 重试队列：没有消费者，消息按**每条消息自己的 TTL** 在这里等待，到期后死信回工作队列。
     *
     * <p>与单体/user-center 同样的取舍：不用队列级 {@code x-message-ttl}——队列参数一旦声明就不能改，
     * 会把"重试间隔"锁死在队列定义上（测试想压到 200ms 就得重建队列）；每条消息带 TTL
     * 则完全由 {@code mall.mq.event-retry-delay-ms} 决定。
     */
    @Bean
    public Queue reviewRetryQueue() {
        return QueueBuilder.durable(MqTopology.REVIEW_RETRY_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.REVIEW_ROUTING_REDELIVER)
                .build();
    }

    @Bean
    public Binding reviewRetryBinding(Queue reviewRetryQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(reviewRetryQueue).to(orderEventExchange)
                .with(MqTopology.REVIEW_ROUTING_RETRY);
    }

    /** 死信队列：无消费者，仅用于观测与人工重投（毒消息不会无限重入工作队列） */
    @Bean
    public Queue reviewDlq() {
        return QueueBuilder.durable(MqTopology.REVIEW_DLQ).build();
    }

    @Bean
    public Binding reviewDlqBinding(Queue reviewDlq, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(reviewDlq).to(orderEventExchange)
                .with(MqTopology.REVIEW_ROUTING_DLQ);
    }
}
