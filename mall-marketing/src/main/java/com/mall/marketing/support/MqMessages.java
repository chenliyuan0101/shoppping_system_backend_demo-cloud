package com.mall.marketing.support;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.mall.common.support.JsonKit;

/**
 * MQ 消息的编解码小工具：统一"JSON 正文 + 自定义头"的约定（P5 步骤 E 从单体共享内核复制的契约副本）。
 *
 * <p>与 {@code mall-review} / {@code mall-user-center} 的同名类逐字同构，**必须如此**：
 * 单体的 {@code OrderEventPublisher#publishRawAfterCommit} 用 {@link #json} 生产消息，
 * 本服务的消费者用 {@link #payload} 解析；重试链路（本服务自己重投）用 {@link #copyOf}。
 * 因此头名（{@code mall-retry-count} / {@code mall-fail-reason}）、正文编码（UTF-8 JSON）、
 * 投递模式（persistent）都不能改——改一个字节就出现"事件到了但解不开"的死信。
 *
 * <p>为什么不用 {@code JacksonJsonMessageConverter} 这类全局转换器：它是**全局**的，一旦注册会影响
 * 所有监听器与模板，还要额外处理类型头({@code __TypeId__})与日期格式。这里显式收发 JSON 字节，
 * 用本服务自带的 {@link JsonKit}，行为完全可预期，且与单体序列化口径一致（record 字段名即 JSON 字段名）。
 */
public final class MqMessages {

    /** 重试次数的自定义消息头（每次重投 +1） */
    public static final String HEADER_RETRY = "mall-retry-count";
    /** 死信原因的消息头（仅便于排查） */
    public static final String HEADER_FAIL_REASON = "mall-fail-reason";

    private MqMessages() {
    }

    /** 构造一条持久化 JSON 消息 */
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

    /** 按原样重投(保留正文与自定义头)，用于"正文本身解析不了"或"重投"的场景 */
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

    /** 解析 JSON 正文（字段名与单体的 {@code OrderClosedMessage} 记录逐字对应） */
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
