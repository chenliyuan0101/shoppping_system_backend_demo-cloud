package com.mall.marketing.service;

/**
 * 券三态命令契约（{@code UNUSED ⇄ LOCKED → USED}），P5 的核心。
 *
 * <pre>
 * lock(memberId, couponMemberId, orderNo)   : 0 → 3，写 order_no
 * use(memberId, couponMemberId, orderNo)    : 3 → 1，写 use_time（order_no 必须匹配）
 * unlock(memberId, couponMemberId, orderNo) : 3 → 0，清 order_no（只动自己锁的那张）
 * </pre>
 *
 * <h2>为什么必须是三个方法而不是两个</h2>
 * 旧实现只有 {@code useCoupon}（{@code 0 → 1}，下单瞬间核销）：一旦下单后事务回滚或订单被取消，
 * 券已经被烧掉，而全仓**没有任何一处把它改回 0**（方案 §4.3.1 ①的存量缺陷）。
 * 三态把"占用"与"核销"拆开：{@code lock} 在下单事务内，{@code use} 在支付成功时，
 * {@code unlock} 在失败/取消/超时时——这三件事发生在**三个不同的时间点**，
 * 用两个方法表达不出来。
 *
 * <h2>幂等与失败语义（调用方必须按这个口径写，批次 4）</h2>
 * <ul>
 *   <li>{@code lock}：同一订单重复 lock → {@code true}（幂等）；被**别的订单**锁住/已核销/已过期
 *       → 抛 409「优惠券已被使用或失效」（<b>对外文案不变</b>，用户看到的还是老提示）；</li>
 *   <li>{@code use}：已经是本单的 {@code USED} → {@code true}（且**不刷新** {@code use_time}）；
 *       不是本单锁的券 → {@code false}。<b>支付回调拿到 false 只记日志，不得让支付失败</b>
 *       （钱已收，方案 §4.3.1 ③）；</li>
 *   <li>{@code unlock}：已经是 {@code UNUSED} → {@code true}（幂等，且不误伤别的券）；
 *       不是本单锁的券 → {@code false}（**不能解锁别人锁的券**）。</li>
 * </ul>
 *
 * <p>三条命令全部是**条件 UPDATE + 影响行数**（见 {@code CouponMemberMapper}），
 * 并发下"只有一方拿到"是数据库保证的，不是应用层判出来的。
 */
public interface CouponCommandService {

    /**
     * 下单占用：CAS {@code 0 → 3} 并记下 {@code orderNo}。前置校验（本人/未过期/模板启用/在窗口内）
     * 与 {@code discountFor} 同源同文案，见实现类注释。
     *
     * @return true = 券现在确实被本单锁定（含幂等命中）；false = 参数不完整，未做任何改动
     * @throws com.mall.marketing.support.BusinessException 400（不可用/已过期/已停用）、
     *         409「优惠券已被使用或失效」（被别人锁住、已核销、已过期状态）
     */
    boolean lock(Long memberId, Long couponMemberId, String orderNo);

    /**
     * 支付核销：CAS {@code 3 → 1}（{@code order_no} 必须匹配），写 {@code use_time}。幂等。
     *
     * @return true = 券已是本单的 USED（含幂等命中）；false = 不是本单锁的券（只记日志）
     */
    boolean use(Long memberId, Long couponMemberId, String orderNo);

    /**
     * 解锁：CAS {@code 3 → 0}，清 {@code order_no}。幂等。
     *
     * @return true = 券已回到 UNUSED（含"本来就是 UNUSED"的幂等命中）；
     *         false = 不是本单锁的券（不能解锁别人锁的券）
     */
    boolean unlock(Long memberId, Long couponMemberId, String orderNo);

    /**
     * <b>每日对账</b>：把"锁太久"的券解锁（P5 步骤 E）。返回本轮实际解锁的行数。
     *
     * <p>为什么需要它：上面三个方法都要求调用方**准确知道那张券**（会员 + 券 id + 单号）。
     * 一旦"关单"这件事本身没发生或没通知到（MQ 丢事件、消费者挂过、人工改库、服务重启打断），
     * 那张券就**没有任何调用方会再来解它**——停在 {@code LOCKED(3)}，对外显示"已使用"，
     * 用户既用不了也看不见。对账是最后一道防线：不依赖任何人通知，只按"锁了多久"来判断。
     *
     * <p>阈值由配置决定（{@code mall.marketing.stuck-lock-hours}，默认 2 小时，必须大于支付超时 30 分钟）。
     * {@code lock_time IS NULL} 的历史遗留行也算"锁太久"（见 {@code CouponMemberMapper#selectStuckLocked}）。
     *
     * <p>幂等：并发/重复执行不会把用户刚锁上的券误放（条件里带同一阈值）。
     *
     * @param limit 单轮上限，避免一次处理过多把服务拖住
     * @return 实际解锁行数（0 表示没有需要收拾的）
     */
    int unlockStuckLocks(int limit);
}
