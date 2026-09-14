package com.mall.usercenter.support;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MQ 消息的编解码小工具：统一"JSON 正文 + 自定义头"的约定。
 *
 * <p>P3-5 从单体共享内核（{@code com.mall.demo.common.MqMessages}）复制的契约副本。
 * <b>必须与单体逐字同构</b>：单体的 {@code OrderEventPublisher} 用 {@link #json} 生产消息，
 * 本服务的消费者用 {@link #payload} 解析；重试链路（本服务自己重投）也用 {@link #copyOf}。
 * 因此头名（{@code mall-retry-count} / {@code mall-fail-reason}）、正文编码（UTF-8 JSON）、
 * 投递模式（persistent）都不能改。
 *
 * <p>为什么不用 {@code JacksonJsonMessageConverter} 这类全局消息转换器：转换器是**全局**的，
 * 一旦注册会影响所有监听器与模板，且要额外处理类型头({@code __TypeId__})、日期格式等细节。
 * 这里显式收发 JSON 字节，用本服务自带的 {@link JsonKit}（Jackson 3），行为完全可预期，
 * 而且与单体的序列化口径一致（记录类型的字段名就是 JSON 字段名）。
 */
public final class MqMessages {

    /** 重试次数的自定义消息头（每次重投 +1） */
    public static final String HEADER_RETRY = "mall-retry-count";
    /** 死信原因的消息头（仅便于排查） */
    public static final String HEADER_FAIL_REASON = "mall-fail-reason";

    private MqMessages() {
    }

    /**
     * 构造一条持久化 JSON 消息。
     *
     * @param payload   消息体(用 {@link JsonKit} 序列化)
     * @param ttlMillis 非空时写入消息 TTL（重试延迟用它；工作队列首发为 null）
     * @param messageId 消息 id（便于在管理台定位，通常是业务主键）
     * @param headers   自定义头(可为 null)
     */
    public static Message json(Object payload, Integer ttlMillis, String messageId, Map<String, Object> headers) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null) {
            props.setMessageId(messageId);
        }
        if (ttlMillis != null && ttlMillis > 0) {
            props.setExpiration(String.valueOf(ttlMillis));
        }
        if (headers != null) {
            headers.forEach(props::setHeader);
        }
        return new Message(JsonKit.toJson(payload).getBytes(StandardCharsets.UTF_8), props);
    }

    /** 按原样重投(保留正文与自定义头)，用于"正文本身解析不了"的场景 */
    public static Message copyOf(Message original, int retry, long ttlMillis, String failReason) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(original.getMessageProperties().getMessageId());
        if (ttlMillis > 0) {
            props.setExpiration(String.valueOf(ttlMillis));
        }
        props.setHeader(HEADER_RETRY, retry);
        if (failReason != null) {
            props.setHeader(HEADER_FAIL_REASON, failReason);
        }
        return new Message(original.getBody(), props);
    }

    /** 解析 JSON 正文（字段名与单体的 {@code OrderEventMessage} 记录逐字对应） */
    public static <T> T payload(Message message, Class<T> type) {
        return JsonKit.toObject(new String(message.getBody(), StandardCharsets.UTF_8), type);
    }

    /** 读取重试次数（没有该头 = 首次投递） */
    public static int retryCount(Message message) {
        Object v = message.getMessageProperties().getHeaders().get(HEADER_RETRY);
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
