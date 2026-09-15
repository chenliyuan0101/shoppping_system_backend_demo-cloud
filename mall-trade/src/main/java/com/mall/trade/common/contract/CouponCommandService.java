package com.mall.trade.common.contract;

import com.mall.common.support.MemberId;

/**
 * 券命令契约（跨服务）：券的**三态**由营销域实现，交易域只表达"锁定/核销/解锁"。
 *
 * <pre>
 * lock(memberId, couponMemberId, orderNo)   : UNUSED(0) → LOCKED(3)，写 order_no（同单幂等）
 * use(memberId, couponMemberId, orderNo)    : LOCKED(3) → USED(1)，写 use_time（order_no 必须匹配）
 * unlock(memberId, couponMemberId, orderNo) : LOCKED(3) → UNUSED(0)，清 order_no（只动自己锁的那张）
 * </pre>
 *
 * <p><b>P5 步骤 C</b>：本接口从 {@code com.mall.trade.sms.service.CouponCommandService} 上移，
 * 语义从旧的"下单即核销 {@code useCoupon}(0→1)"改成"下单锁定"。
 * <b>P5 步骤 E</b> 补上 {@link #use}：核销时机从"下单"挪到"<b>支付成功</b>"，
 * 于是"下单后取消/超时"这条路径才有机会把券还回去（旧实现里下单瞬间就烧掉了，永远回不来——
 * 那是 <b>存量缺陷</b>，方案 §4.3.1 ①）。
 *
 * <p>⚠️ 对外错误码/文案不变：锁定失败（被别人锁住/已核销/已过期状态）仍是
 * {@code 409 优惠券已被使用或失效}——由营销域抛出、{@code MarketingClient} 原样透传。
 */
public interface CouponCommandService {

    /**
     * 锁定券（下单占用）：{@code 0 → 3} 并记 {@code order_no}。同一订单重复 lock 幂等返回 true。
     *
     * @return true = 券现在确实被本单锁定；false = 没锁到（调用方按 409 处理）
     */
    boolean lock(Long memberId, Long couponMemberId, String orderNo);

    /**
     * 核销券（<b>支付成功时</b>调用）：{@code 3 → 1}，写 {@code use_time}。幂等。
     *
     * <p>失败语义与 {@link #unlock} 同口径：<b>返回 false 而不是抛异常</b>。
     * 原因：调用点在"支付已成功、钱已收"之后——此时因为"券没核销成功"把支付判成失败，
     * 会让用户重复支付而订单其实已支付。{@code false} 只记日志，由每日对账/人工核对兜底
     * （方案 §4.3.1 ③）。
     *
     * @return true = 券已是本单的 {@code USED}（含幂等命中）；false = 不是本单锁的券（只记日志）
     */
    boolean use(Long memberId, Long couponMemberId, String orderNo);

    /**
     * 解锁券（下单失败/回滚补偿/取消/超时/后台关单）：{@code 3 → 0}，清 {@code order_no}，
     * 只动自己锁的那张。
     *
     * <p>⚠️ 调用**不抛异常**（见 {@code MarketingClient#unlockQuietly}）：
     * 它可能发生在事务回滚回调里，此时再抛只会掩盖原始失败原因；失败留日志并由对账兜底。
     */
    boolean unlock(Long memberId, Long couponMemberId, String orderNo);
}
