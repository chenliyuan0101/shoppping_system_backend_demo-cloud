package com.mall.product.support;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import com.mall.common.support.JsonKit;

/**
 * MQ 消息编解码小工具（**生产方视角**：只有"造一条 JSON 消息"这一件事）。
 *
 * <p><b>为什么不用 {@code JacksonJsonMessageConverter} 这类全局消息转换器</b>：转换器是<b>全局</b>的，
 * 一旦注册会影响所有监听器与模板，还要额外处理类型头({@code __TypeId__})、日期格式等细节
 * （本项目在 ES 文档时间字段上踩过一次）。这里显式写 JSON 字节，用本模块自带的 {@link JsonKit}，
 * 行为完全可预期 —— 与 {@code mall-search} 的同名工具同口径（那边多两个"重投/原样重投"的方法，
 * 属于消费方职责，本服务不消费该队列，故不抄）。
 *
 * <p>⚠️ 消费方（{@code mall-search} 的 {@code MqMessages.payload}）只读 <b>body 字节</b>并用 Jackson 反序列化，
 * 所以"两边能对上"的关键是 **JSON 字段名**（见 {@code ProductSyncMessage}），而不是这里的头信息。
 */
public final class MqMessages {

    private MqMessages() {
    }

    /**
     * 构造一条持久化 JSON 消息（首投：不带 {@code mall-retry-count} 头，消费方据此判断"首次投递"）。
     *
     * @param payload   消息体（用 {@link JsonKit} 序列化）
     * @param messageId 消息 id（便于在 RabbitMQ 管理台按业务定位；可为 null）
     */
    public static Message json(Object payload, String messageId) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        if (messageId != null) {
            props.setMessageId(messageId);
        }
        return new Message(JsonKit.toJson(payload).getBytes(StandardCharsets.UTF_8), props);
    }
}
