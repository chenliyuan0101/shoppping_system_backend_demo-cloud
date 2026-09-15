package com.mall.usercenter.mq;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mall.usercenter.service.NotificationService;
import com.mall.usercenter.support.MqMessages;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.mall.common.support.MemberId;

/**
 * <b>P4-2 的 user-center 侧验收</b>：{@code order.finished} 不再刷"未知事件类型"WARN，
 * 而既有三条事件的文案/行为<b>逐字不变</b>。
 *
 * <p>为什么要有这个用例（问题本身很隐蔽）：{@code NotificationEventConsumer} 用
 * {@code switch (eventType)} 分派，未知类型走 {@code default} 打一条 WARN。P4-2 起 trade 会为
 * <b>每一笔确认收货</b>发 {@code order.finished}；一旦有人把本服务的队列也绑上那个路由键，
 * 日志里就会出现"每笔订单一条 WARN"，把真正需要被发现的未知类型淹掉。
 *
 * <p>为什么是<b>单元</b>用例而不是真 MQ 用例：这里要断言的正是"日志里有没有那条 WARN"
 * 与"有没有写库"，两者都与 broker 无关；同时它能在没有 RabbitMQ 的机器上跑，不会被 assumption 跳过
 * （真 MQ 的链路回归仍在 {@code NotificationEventMqMySqlTest} 里）。日志用 Logback 的
 * {@link ListAppender} 直接收集，不依赖控制台输出格式。
 *
 * <p>四条：
 * <ol>
 *   <li>{@code ORDER_FINISHED} 类型（完整 OrderEventMessage 形状）→ 不写站内消息、不重投/进死信、
 *       消息被 ack、<b>没有 WARN</b>；</li>
 *   <li>{@code order.finished} 的<b>真实载荷形状</b>（没有 eventType 字段、缺 primitive 的 atMillis）
 *       → 实测<b>解析失败</b>，走"无法解析 → 死信队列"分支（ERROR + DLQ），而不是任何 WARN
 *       —— 这条断言把"计划文档假设的 WARN 噪声其实不会发生"钉成可执行的事实；</li>
 *   <li>既有的 paid/shipped/refund 三条 → 标题与正文<b>逐字</b>与改造前相同（用 mock 抓参数比对）；</li>
 *   <li>真正的未知类型 → WARN 仍然保留（没有把观测信号一起"静默"掉）。</li>
 * </ol>
 */
class NotificationEventConsumerTest {

    private NotificationService notificationService;
    private OrderEventRetryPublisher retryPublisher;
    private NotificationEventConsumer consumer;
    private Channel channel;
    private Logger consumerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        retryPublisher = mock(OrderEventRetryPublisher.class);
        channel = mock(Channel.class);
        when(notificationService.push(any(), any(), any(), any(), any())).thenReturn(true);

        consumer = new NotificationEventConsumer(notificationService, retryPublisher);
        // maxRetry 是 @Value 注入的字段，手工 new 出来的实例需要显式给值
        ReflectionTestUtils.setField(consumer, "maxRetry", 3);

        // 直接收集本消费者这个 logger 的日志事件（不依赖控制台格式，也不依赖 Spring 上下文）
        appender = new ListAppender<>();
        appender.start();
        consumerLogger = (Logger) LoggerFactory.getLogger(NotificationEventConsumer.class);
        consumerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (consumerLogger != null && appender != null) {
            consumerLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("[P4-2] ORDER_FINISHED 事件：不写站内消息、不告警（不再刷「未知事件类型」WARN）")
    void orderFinishedType_isIgnoredSilently() throws Exception {
        Message message = MqMessages.json(
                new OrderEventMessage(OrderEventMessage.TYPE_ORDER_FINISHED, "ORDER-FIN-1", 1001L, null, null,
                        System.currentTimeMillis()),
                null, "ORDER-FIN-1", null);

        consumer.onOrderEvent(message, channel);

        verify(notificationService, never()).push(any(), any(), any(), any(), any());
        verifyNoInteractions(retryPublisher);
        verify(channel).basicAck(anyLong(), eq(false));
        assertFalse(warnMessages().stream().anyMatch(m -> m.contains("未知事件类型")),
                "确认收货事件不该打「未知事件类型」WARN（每笔订单一条会把日志淹掉），实际 WARN=" + warnMessages());
    }

    @Test
    @DisplayName("[P4-2 实测] order.finished 的真实载荷形状解析不了 → 走「无法解析→死信」而不是 WARN")
    void orderFinishedRealPayloadShape_failsToParseAndGoesToDlq() throws Exception {
        // 与单体 OrderFinishedMessage 的 JSON 逐字同形：六个字段里只有 orderNo/memberId 对得上，
        // 没有 eventType，也没有 primitive 的 atMillis
        Message message = MqMessages.json(Map.of(
                "orderNo", "ORDER-FIN-2",
                "memberId", 1001L,
                "finishedTime", System.currentTimeMillis(),
                "items", List.of(Map.of("orderItemId", 1L, "spuId", 2L, "skuId", 3L,
                        "spuTitle", "t", "skuImage", "i", "quantity", 1))),
                null, "ORDER-FIN-2", null);

        consumer.onOrderEvent(message, channel);

        verify(notificationService, never()).push(any(), any(), any(), any(), any());
        verify(channel).basicAck(anyLong(), eq(false));
        // 这条断言钉住一个**实测事实**（与计划文档的假设不同，值得留在这里当证据）：
        // 本项目的 Jackson 3 默认开启 FAIL_ON_NULL_FOR_PRIMITIVES，缺失的 primitive atMillis
        // 让反序列化直接抛 MismatchedInputException → 消费者走"无法解析 → 死信队列"分支，
        // 于是它**永远不会**以 eventType=null 的形式到达 switch，也就不会打"未知事件类型"WARN。
        verify(retryPublisher).publishRawToDlq(eq(message), anyInt(), anyString());
        assertEquals(1, errorMessages().stream().filter(m -> m.contains("无法解析")).count(),
                "真实形状的 order.finished 若被误绑到本服务的队列，现象是 ERROR + 死信（响亮），实际=" + errorMessages());
        assertFalse(warnMessages().stream().anyMatch(m -> m.contains("未知事件类型")),
                "它不是「未知事件类型」，不该刷那条 WARN（真正的未知类型才走 default），实际 WARN=" + warnMessages());
    }

    @Test
    @DisplayName("[回归] 既有三条事件（paid/shipped/refund）的文案与改造前逐字一致")
    void existingThreeEvents_keepByteIdenticalText() throws Exception {
        String orderNo = "ORDER-TEXT-1";

        consumer.onOrderEvent(MqMessages.json(
                OrderEventMessage.paid(orderNo, 1001L, 12345L), null, orderNo, null), channel);
        consumer.onOrderEvent(MqMessages.json(
                OrderEventMessage.shipped(orderNo, 1001L, "顺丰速运 SF-1"), null, orderNo, null), channel);
        consumer.onOrderEvent(MqMessages.json(
                OrderEventMessage.refundSettled(orderNo, 1001L, 9900L), null, orderNo, null), channel);

        verify(notificationService).push(1001L, OrderEventMessage.TYPE_ORDER_PAID, "支付成功",
                "订单 " + orderNo + " 已支付成功，实付 123.45 元，我们会尽快为你发货。", orderNo);
        verify(notificationService).push(1001L, OrderEventMessage.TYPE_ORDER_SHIPPED, "商品已发货",
                "订单 " + orderNo + " 已发货，物流：顺丰速运 SF-1。请留意收货。", orderNo);
        verify(notificationService).push(1001L, OrderEventMessage.TYPE_REFUND_SETTLED, "退款已到账",
                "订单 " + orderNo + " 的退款 99.00 元已处理完成，请查收。", orderNo);
        verify(notificationService, times(3)).push(any(), any(), any(), any(), any());
        assertTrue(warnMessages().isEmpty(), "既有三条事件不该产生任何 WARN，实际=" + warnMessages());
    }

    @Test
    @DisplayName("[回归] 真正的未知类型仍然告警（观测信号没有被一起静默掉）")
    void unknownType_stillWarns() throws Exception {
        Message message = MqMessages.json(
                new OrderEventMessage("SOMETHING_ELSE", "ORDER-X", 1001L, null, null, 0L), null, "ORDER-X", null);

        consumer.onOrderEvent(message, channel);

        verify(notificationService, never()).push(any(), any(), any(), any(), any());
        verify(channel).basicAck(anyLong(), eq(false));
        assertEquals(1, warnMessages().stream().filter(m -> m.contains("未知事件类型")).count(),
                "未知类型必须仍然打一条 WARN，实际 WARN=" + warnMessages());
    }

    // ==================== helpers ====================

    private List<String> warnMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private List<String> errorMessages() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
