package com.mall.marketing.support.constant;

/**
 * 用户优惠券状态 {@code sms_coupon_member.coupon_status}：0 未使用 / 1 已使用 / 2 已过期 / <b>3 锁定中</b>。
 *
 * <p><b>P5 从两态改成三态</b>（《微服务改造方案.md》§4.3、.dsh-notes/P5-remaining-plan.md）：
 * <pre>
 * UNUSED ──lock(orderNo)──► LOCKED ──use(orderNo)──► USED
 *    ▲                         │
 *    └────unlock(orderNo)──────┘
 * </pre>
 * 旧实现的类注释写着"取消/关单再改回 {@code 0}"，但**全仓没有任何一处把 {@code coupon_status}
 * 改回 UNUSED**——用了券的订单只要取消/超时，券就被永久烧掉（库存回了、券没回）。
 * {@link #LOCKED} + {@code unlock} 正是补上这半句注释（方案 §4.3.1 ①：这是修既有缺陷，不是新能力）。
 *
 * <p>⚠️ <b>{@link #LOCKED} 必须等于 3，不能等于 2</b>（C1 硬约束，方案 §4.3.1 ②）：
 * 前端已经把这套词表钉死——
 * {@code frontend_web/vue-web/src/views/CouponCenter.vue} L90-91（{@code 1→used}、{@code 2→expired}）
 * 与 {@code frontend_admin/vue-admin/src/views/coupon/CouponList.vue} L82
 * （{@code ['未使用','已使用','已过期'][couponStatus]}）。若 {@code LOCKED} 用 2，
 * 锁定中的券会被前端显示成"已过期"。
 * 库里的 3 在**对外响应**里投影回 1（见 {@code CouponStatusProjection}）——
 * 因为旧实现里下单瞬间券就已 USED，锁定窗口内对外必须仍是"已使用"。
 *
 * <p>⚠️ 本类**不新增 {@code = 1} 的同义词**（比如 {@code RELEASED}）：
 * "解锁回未使用"复用 {@link #UNUSED}，这是"回滚到原状态"而不是"进入一个新状态"，
 * 多一个等价值只会让 {@code ==} 判断在别处悄悄失效。
 */
public final class CouponMemberStatus {

    public static final int UNUSED = 0;
    public static final int USED = 1;
    public static final int EXPIRED = 2;

    /** 锁定中（P5 新增：下单占用、支付核销、取消/超时解锁） */
    public static final int LOCKED = 3;

    private CouponMemberStatus() {
    }
}
