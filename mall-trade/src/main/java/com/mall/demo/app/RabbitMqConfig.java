package com.mall.demo.app;

import com.mall.demo.common.MqTopology;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑<b>装配</b>：把三套拓扑（订单超时关单 / 商品索引同步 / 领域事件）声明成 Bean。
 *
 * <p>交换机、队列、路由键的<b>名字</b>已下沉到共享内核 {@link MqTopology}——
 * 那是生产方与消费方之间的契约，业务代码只依赖 {@code MqTopology}；
 * 本类属于组装根（{@code app} 包），只负责"把契约声明成基础设施"。
 * 这样拆服务时：契约跟着共享内核走，每个服务各持一份自己的装配。
 *
 * <p>三套拓扑的形状与各自的设计取舍，见 {@link MqTopology} 的类注释；本类型只补充装配侧的两个要点：
 * <ul>
 *   <li><b>延迟队列不设消费者</b>：消息只负责"睡到点"，过期后被死信投递到工作队列才被消费</li>
 *   <li><b>消费失败进死信队列</b>：毒消息(反序列化失败/订单数据异常)不会无限重回队列；
 *       死信里的消息由 {@code GET /api/admin/mq/ping} 暴露出来，配合兜底扫描任务不影响业务</li>
 * </ul>
 *
 * <p>⚠️ 已知边界：按消息 TTL 的死信只在消息到达队首时才判定过期。订单超时链路所有消息的 TTL 都是
 * "支付超时(默认 30 分钟)"，先进先出即先到期，不存在队首阻塞；若将来出现"TTL 长短不一"的场景
 * (例如给部分订单压缩支付时间)，需要改为延迟交换机插件或按 TTL 分队列。
 *
 * <p>TODO(P0 任务 4)：按服务拆分本类——超时关单留在 trade、索引同步归 search、领域事件由各自服务的
 * 队列声明承担（一条队列的消费者在哪个服务，装配就应在哪个服务）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.mq.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMqConfig {

    @Bean
    public DirectExchange orderDelayExchange() {
        return ExchangeBuilder.directExchange(MqTopology.DELAY_EXCHANGE).durable(true).build();
    }

    /** 延迟队列：绑定死信交换机/路由键 → TTL 到期后进入工作队列 */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(MqTopology.DELAY_QUEUE)
                .deadLetterExchange(MqTopology.WORK_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.WORK_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderDelayBinding(Queue orderDelayQueue, DirectExchange orderDelayExchange) {
        return BindingBuilder.bind(orderDelayQueue).to(orderDelayExchange).with(MqTopology.DELAY_ROUTING_KEY);
    }

    @Bean
    public DirectExchange orderWorkExchange() {
        return ExchangeBuilder.directExchange(MqTopology.WORK_EXCHANGE).durable(true).build();
    }

    /** 关单消费队列：消费失败(nack 不重回队列) → 死信队列 */
    @Bean
    public Queue orderTimeoutQueue() {
        return QueueBuilder.durable(MqTopology.WORK_QUEUE)
                .deadLetterExchange(MqTopology.WORK_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderTimeoutBinding(Queue orderTimeoutQueue, DirectExchange orderWorkExchange) {
        return BindingBuilder.bind(orderTimeoutQueue).to(orderWorkExchange).with(MqTopology.WORK_ROUTING_KEY);
    }

    @Bean
    public Queue orderTimeoutDlq() {
        return QueueBuilder.durable(MqTopology.DLQ).build();
    }

    @Bean
    public Binding orderTimeoutDlqBinding(Queue orderTimeoutDlq, DirectExchange orderWorkExchange) {
        return BindingBuilder.bind(orderTimeoutDlq).to(orderWorkExchange).with(MqTopology.DLQ_ROUTING_KEY);
    }

    // ==================== 商品索引同步拓扑 ====================

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
     * <p>刻意不用队列级 {@code x-message-ttl}：队列参数一旦声明就不能改，会把"重试间隔"
     * 锁死在队列定义上（测试想压到几百毫秒就得重建队列）。每条消息带 TTL 则完全由配置决定。
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

    // ==================== 领域事件拓扑(topic) ====================

    @Bean
    public TopicExchange orderEventExchange() {
        return ExchangeBuilder.topicExchange(MqTopology.EVENT_EXCHANGE).durable(true).build();
    }

    /** 事件工作队列：三个业务事件 + "重试归来"的重投键；消费失败(nack 不重回队列) → 死信 */
    @Bean
    public Queue orderEventQueue() {
        return QueueBuilder.durable(MqTopology.EVENT_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.EVENT_ROUTING_DLQ)
                .build();
    }

    @Bean
    public Binding orderEventPaidBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_PAID);
    }

    @Bean
    public Binding orderEventShippedBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_SHIPPED);
    }

    @Bean
    public Binding orderEventRefundBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_REFUND);
    }

    /** 重试归来：只绑工作队列，避免与重试队列互相"抢"同一条消息造成死循环 */
    @Bean
    public Binding orderEventRedeliverBinding(Queue orderEventQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_REDELIVER);
    }

    @Bean
    public Queue orderEventRetryQueue() {
        return QueueBuilder.durable(MqTopology.EVENT_RETRY_QUEUE)
                .deadLetterExchange(MqTopology.EVENT_EXCHANGE)
                .deadLetterRoutingKey(MqTopology.EVENT_ROUTING_REDELIVER)
                .build();
    }

    @Bean
    public Binding orderEventRetryBinding(Queue orderEventRetryQueue, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventRetryQueue).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_RETRY);
    }

    @Bean
    public Queue orderEventDlq() {
        return QueueBuilder.durable(MqTopology.EVENT_DLQ).build();
    }

    @Bean
    public Binding orderEventDlqBinding(Queue orderEventDlq, TopicExchange orderEventExchange) {
        return BindingBuilder.bind(orderEventDlq).to(orderEventExchange).with(MqTopology.EVENT_ROUTING_DLQ);
    }
}
