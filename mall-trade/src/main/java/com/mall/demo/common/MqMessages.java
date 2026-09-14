package com.mall.demo.common;

import com.mall.demo.common.JsonKit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * MQ 消息的编解码小工具：统一"JSON 正文 + 自定义头"的约定。
 *
 * <p>为什么不用 {@code JacksonJsonMessageConverter} 这类全局消息转换器：
 * 转换器是**全局**的，一旦注册会影响所有监听器与模板，且要额外处理类型头({@code __TypeId__})、
 * 日期格式等细节（本项目在 ES 文档时间字段上已经踩过一次）。这里显式收发 JSON 字节，
 * 用项目自带的 Jackson 3 工具 {@link JsonKit}，行为完全可预期。
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
     * @param payload   消息体(用 JsonKit 序列化)
     * @param ttlMillis 非空时写入消息 TTL（延迟消息 / 重试延迟都用它）
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

    /**
     * 从**已经序列化好的 JSON 正文字符串**构造消息（P8-3 发件箱重投用）。
     *
     * <p>为什么不复用 {@link #json(Object, Integer, String, Map)}：那个方法接收的是**对象**，会再序列化一次；
     * 发件箱里存的是"入箱那一刻的字节"。重投时**原样发出存下来的字节**，才能保证
     * "经过发件箱的消息"与"直投的消息"在消费侧**逐字节相同**（否则一旦序列化实现有变，重投的消息就与首次的形态不一致）。
     *
     * <p>除正文来源之外，属性与 {@code json(payload, null, messageId, null)} **完全一致**
     * （JSON 内容类型 + UTF-8 + 持久化 + 同一个 messageId），所以消费方一行都不用改。
     */
    public static Message raw(String jsonBody, String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null) {
            props.setMessageId(messageId);
        }
        return new Message((jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8), props);
    }

    /** 按原样重投(保留正文与自定义头)，用于"正文本身解析不了"的场景 */
    public static Message copyOf(Message original, int retry, long ttlMillis, String failReason) {        MessageProperties props = new MessageProperties();
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

    /** 解析 JSON 正文 */
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
