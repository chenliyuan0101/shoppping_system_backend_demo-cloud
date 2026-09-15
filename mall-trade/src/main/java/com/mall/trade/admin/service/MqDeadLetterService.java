package com.mall.trade.admin.service;

import com.mall.trade.admin.dto.MqDlqRequeueVO;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.MqMessages;
import com.mall.trade.common.MqTopology;
import com.mall.common.support.PageKit;
import com.mall.trade.oms.mq.OrderEventMessage;
import com.mall.trade.oms.mq.OrderEventPublisher;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 死信队列重投：把死信里的消息重新投回对应的**工作队列**，让消费者再处理一次。
 *
 * <p>为什么需要：管理台手工 Requeue 只能一条条点，运维不方便；而且手工重投常常需要
 * "先修好下游（ES/DB），再批量放回去"，这里把这件事变成一个接口。
 *
 * <p>两个实现要点：
 * <ol>
 *   <li>用 {@code RabbitTemplate.receive(queue)}（basic.get + autoAck）把消息**取出来**，
 *       再按目标工作队列的路由键重新投递；取出来的消息不会自己回到死信队列</li>
 *   <li><b>重投时丢掉重试次数头</b>（重新计一轮重试）。否则消息带着"已重试 N 次"回去，
 *       下次失败会立刻再进死信，等于白投</li>
 * </ol>
 *
 * <p>注意：重投**不改消息内容**，所以"因为数据本身有问题"的消息（正文无法解析）会再次
 * 走"重试 → 死信"回到死信队列。这是预期行为——这类消息要人工看内容后在管理台处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqDeadLetterService {

    /** 默认单次重投上限 */
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    /** 死信队列 → 目标(交换机, 路由键) 白名单：只允许重投这三个队列，避免误操作 */
    private static final Map<String, String[]> ROUTES = Map.of(
            MqTopology.DLQ, new String[]{MqTopology.WORK_EXCHANGE, MqTopology.WORK_ROUTING_KEY},
            MqTopology.SYNC_DLQ, new String[]{MqTopology.SYNC_EXCHANGE, MqTopology.SYNC_ROUTING_KEY},
            MqTopology.EVENT_DLQ, new String[]{MqTopology.EVENT_EXCHANGE, MqTopology.EVENT_ROUTING_REDELIVER}
    );

    /** 重投全部时的固定顺序（Map.of 无序，输出/日志会飘，这里显式定序便于对照） */
    private static final List<String> ALL_DLQS = List.of(
            MqTopology.DLQ, MqTopology.SYNC_DLQ, MqTopology.EVENT_DLQ);

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;

    @Value("${mall.mq.enabled:true}")
    private boolean mqEnabled;

    /** 可重投的死信队列名（给接口提示用） */
    public List<String> supportedQueues() {
        return new ArrayList<>(ROUTES.keySet());
    }

    /** 重投单个死信队列；返回 {queue, requeued, remaining} */
    public MqDlqRequeueVO requeue(String dlq, Integer limit) {
        if (!mqEnabled) {
            throw new BusinessException(400, "RabbitMQ 已关闭(mall.mq.enabled=false)，无法重投死信");
        }
        String[] route = ROUTES.get(dlq);
        if (route == null) {
            throw new BusinessException(400, "不支持的死信队列：" + dlq + "；可选：" + String.join(", ", ROUTES.keySet()));
        }
        int max = limit == null ? DEFAULT_LIMIT : (int) PageKit.size(limit, MAX_LIMIT);
        int requeued = 0;
        int failed = 0;
        for (int i = 0; i < max; i++) {
            Boolean ok = requeueOne(dlq, route[0], route[1]);
            if (ok == null) {
                break;   // 死信队列已空
            }
            if (ok) {
                requeued++;
            } else {
                failed++;
            }
        }
        long remaining = queueDepth(dlq);
        log.info("死信重投完成: dlq={} 重投 {} 条，失败 {} 条，剩余 {} 条", dlq, requeued, failed, remaining);

        MqDlqRequeueVO result = new MqDlqRequeueVO();
        result.setQueue(dlq);
        result.setRequeued(requeued);
        result.setFailed(failed);
        result.setRemaining(remaining);
        return result;
    }

    /**
     * 取一条死信并重投。
     *
     * <p><b>为什么不用 {@code RabbitTemplate#receive}</b>：它是 {@code basicGet(autoAck=true)}，
     * 消息取出即被确认——如果紧接着的重投失败（broker 抖动/路由异常），这条消息就**凭空消失**了。
     * 这里用 {@code basicGet(autoAck=false)} + 重投成功后再 {@code basicAck}，
     * 失败则 {@code basicNack(requeue=true)} 把消息**放回死信队列**，做到"要么重投成功、要么还在原地"。
     *
     * @return null=队列已空；true=重投成功；false=重投失败(消息已放回死信)
     */
    private Boolean requeueOne(String dlq, String exchange, String fallbackRoutingKey) {
        return rabbitTemplate.execute(channel -> {
            GetResponse got = channel.basicGet(dlq, false);
            if (got == null) {
                return null;
            }
            long deliveryTag = got.getEnvelope().getDeliveryTag();
            try {
                AMQP.BasicProperties props = freshProps(got.getProps());
                String routingKey = resolveRoutingKey(fallbackRoutingKey, got.getBody(), props);
                channel.basicPublish(exchange, routingKey, props, got.getBody());
                channel.basicAck(deliveryTag, false);
                return Boolean.TRUE;
            } catch (Exception e) {
                log.error("死信重投失败，消息放回队列: dlq={} 原因={}", dlq, e.getMessage());
                try {
                    channel.basicNack(deliveryTag, false, true);
                } catch (Exception nackError) {
                    log.error("死信消息回队也失败(需人工处理): dlq={} 原因={}", dlq, nackError.getMessage());
                }
                return Boolean.FALSE;
            }
        });
    }

    /**
     * 重投用的消息属性：清掉重试次数/失败原因头，让消息**重新计一轮重试**；
     * 同时清掉 `expiration`（延迟消息死信后会带着原 TTL，重回工作队列时不该再立刻过期）。
     */
    private AMQP.BasicProperties freshProps(AMQP.BasicProperties original) {
        Map<String, Object> headers = original.getHeaders() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(original.getHeaders());
        headers.remove(MqMessages.HEADER_RETRY);
        headers.remove(MqMessages.HEADER_FAIL_REASON);
        return original.builder()
                .headers(headers)
                .expiration(null)
                .build();
    }

    /** 把 AMQP 的原始属性包成 Spring 的 MessageProperties（仅用于解析正文里的 eventType） */
    private org.springframework.amqp.core.MessageProperties toProperties(AMQP.BasicProperties props) {
        org.springframework.amqp.core.MessageProperties result = new org.springframework.amqp.core.MessageProperties();
        Map<String, Object> headers = props == null ? Map.of() : (props.getHeaders() == null ? Map.of() : props.getHeaders());
        headers.forEach(result::setHeader);
        return result;
    }

    /** 重投全部死信队列 */
    public MqDlqRequeueVO requeueAll(Integer limit) {
        List<MqDlqRequeueVO> results = new ArrayList<>();
        int total = 0;
        for (String dlq : ALL_DLQS) {
            MqDlqRequeueVO one = requeue(dlq, limit);
            total += one.getRequeued();
            results.add(one);
        }
        MqDlqRequeueVO data = new MqDlqRequeueVO();
        data.setTotalRequeued(total);
        data.setResults(results);
        return data;
    }

    /**
     * 事件死信按消息体里的 eventType 回到对应的业务路由键（这样消费者正常处理）。
     * 解析不出来时回落到"重投键"（工作队列也绑了这个键），让消费者按自己的逻辑处理/再进死信。
     */
    private String resolveRoutingKey(String fallback, byte[] body, AMQP.BasicProperties props) {
        if (!MqTopology.EVENT_ROUTING_REDELIVER.equals(fallback)) {
            return fallback;   // 订单关单 / 索引同步两条链路的路由键是固定的，直接用
        }
        try {
            Message wrapper = new Message(body, toProperties(props));
            OrderEventMessage event = MqMessages.payload(wrapper, OrderEventMessage.class);
            return OrderEventPublisher.routingKey(event.eventType());
        } catch (Exception e) {
            return fallback;
        }
    }

    private long queueDepth(String queue) {
        try {
            var info = amqpAdmin.getQueueInfo(queue);
            return info == null ? 0 : info.getMessageCount();
        } catch (Exception e) {
            return -1;
        }
    }
}
