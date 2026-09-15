package com.mall.marketing.mq;

import com.mall.common.support.MemberId;

/**
 * 订单关闭事件载荷（P5 步骤 E）：<b>单体 {@code com.mall.demo.oms.mq.OrderClosedMessage} 的契约副本</b>。
 *
 * <p>字段名与顺序必须与单体那个 record **逐字一致**：单体的
 * {@code OrderEventPublisher.publishRawAfterCommit} 用 Jackson 序列化这个 record，
 * 本服务用 {@code MqMessages.payload(message, OrderClosedMessage.class)} 反序列化——
 * 字段名即 JSON 字段名，改一个就出现"事件到了但解不开"的死信。
 *
 * <p>消费语义（见 {@link OrderClosedConsumer}）：拿 {@code couponMemberId} 与 {@code orderNo}
 * 再调一次 {@code unlock}（幂等）。这是**第二道防线**：
 * <ol>
 *   <li>第一道：单体在关单事务里**同步**调 {@code unlock}（多数情况够用）；</li>
 *   <li>第二道：本事件（事务提交后投递，覆盖"同步调用失败/进程重启"）；</li>
 *   <li>第三道：本服务的每日对账（按 {@code lock_time} 扫"锁太久"的券，覆盖"事件也丢了"）。</li>
 * </ol>
 * 三层叠加的理由很实在：券卡在 {@code LOCKED} 时用户"既用不了也看不见"，
 * 而第三道要等到第二天，中间这段时间只能靠事件兜住。
 *
 * @param orderNo        订单号（幂等与排查主键）
 * @param memberId       下单会员（排查用；不参与业务判定）
 * @param couponMemberId 被锁定的用户券 id；**null = 该单没用券**（本服务直接 ack）
 * @param reason         {@code CANCEL} / {@code TIMEOUT} / {@code ADMIN_CLOSE}
 * @param closedTime     关闭时刻（epoch millis）
 */
public record OrderClosedMessage(String orderNo,
                                 Long memberId,
                                 Long couponMemberId,
                                 String reason,
                                 long closedTime) {

    public static final String REASON_CANCEL = "CANCEL";
    public static final String REASON_TIMEOUT = "TIMEOUT";
    public static final String REASON_ADMIN_CLOSE = "ADMIN_CLOSE";
}
