package com.mall.marketing.config;

import com.mall.marketing.support.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 营销域的 MQ 拓扑声明（P5 步骤 E）：交换机 + <b>本服务自己的</b>工作/重试/死信队列与绑定。
 *
 * <h2>为什么声明交换机而不是"只声明队列就完事"</h2>
 * {@link MqTopology#EVENT_EXCHANGE}（{@code mall.oms.event}）由发布方与各消费方**各自声明一次**。
 * RabbitMQ 以第一次声明的参数为准，后到者只要有一点不同就 {@code PRECONDITION_FAILED}（经典事故）。
 * 实测该交换机是 {@code type=topic durable=true auto_delete=false arguments={}}，
 * 因此这里写 {@code new TopicExchange(EVENT_EXCHANGE, true, false)}——与单体、review、user-center 一字不差。
 * 绑定（Binding）**不属于**声明参数，可以各自增删：扇出正是靠"新增绑定"实现的。
 *
 * <h2>三条队列的分工（与 review 的链路同构）</h2>
 * <pre>
 * EVENT_EXCHANGE (topic) ── order.closed ──► [mall.marketing.order-closed] ──► 消费者 unlock（幂等）
 *        ▲                                          │ 处理失败：投到 ↓ 并带 TTL
 *        │                                          ▼
 *        ├── marketing.retry ──► [mall.marketing.order-closed.retry]（无消费者，等 TTL 到期）
 *        │                                    │ TTL 到期死信回工作队列
 *        │                                    └── marketing.redeliver ──► 工作队列
 *        └── marketing.dlq ────► [mall.marketing.order-closed.dlq]（重试超限 / 正文解析失败）
 * </pre>
 * 重试队列**刻意**用"每条消息自己的 TTL"而不是队列级 {@code x-message-ttl}：队列参数一经声明就不能改，
 * 会把"重试间隔"锁死在队列定义上（想调快就得删队列）。
 *
 * <h2>开关：{@code mall.mq.enabled}（默认 false）</h2>
 * false ⇒ 本配置类**整个不装配** ⇒ 本服务完全不连 RabbitMQ，没有 broker 的机器也能跑全部用例。
 * 开发环境要打开它：{@code MALL_MQ_ENABLED=true}（或 {@code --mall.mq.enabled=true}）。
 * ⚠️ 这条**刻意不写进 {@code application-dev.yaml}**：profile 专属配置优先级高于
 * {@code src/test/resources/application.properties}，写在那里会让每个测试上下文都去连 broker
 * （"本机没起 RabbitMQ 就跑不了测试"，且症状是"测试偶尔超时"这种最难查的形态）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    // ==================== 交换机（与单体共享，参数必须一致） ====================

    @Bean
    public TopicExchange orderEventExchange() {
        return new TopicExchange(MqTopology.EVENT_EXCHANGE, true, false);
    }

    // ==================== 工作队列 + 绑定 ====================

    /** 工作队列：处理失败/超限的消息死信到本服务私有的 dlq 路由键 */
    @Bean
    public Queue marketingOrderClosedQueue() {
        return QueueBuilder.durable(MqTopology.MARKETING_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.MARKETING_ROUTING_DLQ)
                .build();
    }

    /** 只绑 {@code order.closed}：本服务只关心关单事件（绑定就是"过滤器"） */
    @Bean
    public Binding marketingOrderClosedBinding(Queue marketingOrderClosedQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(marketingOrderClosedQueue).to(orderEventExchange)
                .with(MqTopology.EVENT_ROUTING_CLOSED);
    }

    /** 重投回工作队列的绑定（重试队列 TTL 到期后按这个键回来） */
    @Bean
    public Binding marketingRedeliverBinding(Queue marketingOrderClosedQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(marketingOrderClosedQueue).to(orderEventExchange)
                .with(MqTopology.MARKETING_ROUTING_REDELIVER);
    }

    // ==================== 重试队列（没有消费者） ====================

    @Bean
    public Queue marketingOrderClosedRetryQueue() {
        return QueueBuilder.durable(MqTopology.MARKETING_RETRY_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.MARKETING_ROUTING_REDELIVER)
                .build();
    }

    @Bean
    public Binding marketingRetryBinding(Queue marketingOrderClosedRetryQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(marketingOrderClosedRetryQueue).to(orderEventExchange)
                .with(MqTopology.MARKETING_ROUTING_RETRY);
    }

    // ==================== 死信队列（仅观测/人工重投） ====================

    @Bean
    public Queue marketingOrderClosedDlq() {
        return QueueBuilder.durable(MqTopology.MARKETING_DLQ).build();
    }

    @Bean
    public Binding marketingDlqBinding(Queue marketingOrderClosedDlq, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(marketingOrderClosedDlq).to(orderEventExchange)
                .with(MqTopology.MARKETING_ROUTING_DLQ);
    }
}
