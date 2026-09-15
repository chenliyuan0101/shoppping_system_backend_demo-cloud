package com.mall.trade.oms.mq;

import com.mall.common.support.MemberId;

/**
 * 订单关闭事件载荷（P5 步骤 E，路由键 {@code order.closed}，交换机 {@code mall.oms.event}）。
 *
 * <p><b>谁在消费</b>：{@code mall-marketing}（自己的队列 {@code mall.marketing.order-closed}，
 * 与其它服务**扇出**而非竞争）——它拿 {@code orderNo + couponMemberId} 再做一次 {@code unlock}。
 *
 * <p><b>为什么要有这条事件</b>：关单路径（会员取消 / 超时关单 / 后台关单）里已经**同步**调过
 * {@code unlock} 了。同步调用的前提是"目标服务当时可达"——服务重启、网络抖动、超时都可能让它失败。
 * 事件是**事务提交后**投递的第二道防线：marketing 消费后幂等地再解一次；
 * 第三道防线是营销域每日对账（按 {@code lock_time} 扫"锁太久"的券）。
 * 三层叠加的理由很实在：**券卡在 LOCKED 时用户是"既用不了也看不见"**，
 * 而对账要等到第二天，中间这一段时间只能靠事件兜住。
 *
 * <p><b>为什么载荷不复用 {@code OrderEventMessage}</b>：那个形状被 paid/shipped/refund 三条事件共用，
 * user-center 的通知消费者按它反序列化；为关单加字段会影响别人的契约（P4 为 {@code order.finished}
 * 也做过同样判断）。这里的形状只服务本事件，将来加字段也不会波及别人。
 *
 * <p><b>为什么 {@code couponMemberId} 可能为 null</b>：没用券的订单关单时也会发这条事件
 * （保持"每次关单都有一条事件"的简单语义，将来 product/stock 之类的消费者可以直接接上）。
 * 消费者对 {@code null} 直接 ack、不做任何事。
 *
 * @param orderNo        订单号（幂等与排查的主键）
 * @param memberId       下单会员（排查用；不用于业务判定）
 * @param couponMemberId 被锁定的用户券 id；null = 该单没用券
 * @param reason         关闭原因：{@code CANCEL} / {@code TIMEOUT} / {@code ADMIN_CLOSE}
 * @param closedTime     关闭时刻（epoch millis；与既有事件一致用毫秒，避免时区歧义）
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
