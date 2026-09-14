package com.mall.marketing.support;

import com.mall.marketing.domain.Coupon;
import com.mall.marketing.domain.CouponMember;
import com.mall.marketing.support.constant.CouponMemberStatus;
import com.mall.marketing.support.constant.CouponStatus;
import com.mall.marketing.support.constant.CouponValidType;

import java.time.LocalDateTime;

/**
 * 优惠券可用性规则(用户端领取/结算试算、后台展示共用，避免多处各写一套判断)。
 * 约定：模板 status 0=启用、1=停用；validType 1=固定有效期、2=领取后 N 天。
 *
 * <p><b>P0 批次 8b 时它被刻意留在营销域</b>（没有随其它常量下沉 {@code common.constant}）：
 * 它是**业务规则**，不是共享词汇——"券怎么算"的知识不该出现在交易域。
 * P5 把它整体搬进 mall-marketing，本类与单体 {@code com.mall.demo.sms.support.CouponRules}
 * <b>逐字相同</b>（唯一新增的是 {@link #lockedBy}，三态带来的新判据）。
 *
 * <p>⚠️ 本类的方法**全部是纯判断**，不查库、不抛异常；"哪一项不过就抛哪条文案"留在
 * {@code CouponQueryServiceImpl}/{@code CouponCommandServiceImpl} 里——
 * 因为**校验顺序本身就是契约**（5 条文案的先后由顺序决定：状态 → 过期 → 停用 → 窗口 → 门槛）。
 */
public final class CouponRules {

    private CouponRules() {
    }

    /** 模板是否启用(status=0) */
    public static boolean enabled(Coupon coupon) {
        return coupon != null && coupon.getStatus() != null && coupon.getStatus() == CouponStatus.ENABLED;
    }

    /** 模板当前是否在可领/可用时间窗内 */
    public static boolean inValidWindow(Coupon coupon, LocalDateTime now) {
        if (coupon == null || coupon.getValidType() == null) {
            return true;
        }
        if (coupon.getValidType() == CouponValidType.FIXED_RANGE) {
            return (coupon.getValidStartTime() == null || !coupon.getValidStartTime().isAfter(now))
                    && (coupon.getValidEndTime() == null || !coupon.getValidEndTime().isBefore(now));
        }
        return true;   // validType=2：领取后 N 天有效，模板本身总是可领
    }

    /** 订单金额是否满足券门槛 */
    public static boolean meetsThreshold(Coupon coupon, long goodsTotal) {
        return coupon == null || meetsThreshold(coupon.getThresholdAmount(), goodsTotal);
    }

    /** 按门槛值判断(适用于只有门槛字段的 VO，如 MyCouponVO) */
    public static boolean meetsThreshold(Long thresholdAmount, long goodsTotal) {
        return thresholdAmount == null || goodsTotal >= thresholdAmount;
    }

    /** 用户券是否未过期(未使用且未过 expireTime) */
    public static boolean notExpired(CouponMember member, LocalDateTime now) {
        return member != null && notExpired(member.getExpireTime(), now);
    }

    /** 按过期时间判断(适用于只有 expireTime 的 VO，如 MyCouponVO) */
    public static boolean notExpired(LocalDateTime expireTime, LocalDateTime now) {
        return expireTime == null || expireTime.isAfter(now);
    }

    /** 综合判断：模板启用 + 在有效窗内 + 满足门槛(用于结算试算/领取列表过滤) */
    public static boolean usable(Coupon coupon, long goodsTotal, LocalDateTime now) {
        return enabled(coupon) && inValidWindow(coupon, now) && meetsThreshold(coupon, goodsTotal);
    }

    /**
     * 该券是否**正被指定订单锁定**（P5 三态新增：{@code lock} 的幂等判据）。
     *
     * <p>两个条件缺一不可：状态是 {@code LOCKED} <b>且</b> {@code order_no} 就是本单。
     * 只看状态会把"别人锁定的券"误判成本单已锁（那就不是幂等，是丢失冲突）；
     * 只看 order_no 会把"已核销/已解锁"的券也算成锁定（那会让 {@code lock} 对已用掉的券返回 true，
     * 用户以为券还在）。
     */
    public static boolean lockedBy(CouponMember member, String orderNo) {
        return member != null
                && member.getCouponStatus() != null
                && member.getCouponStatus() == CouponMemberStatus.LOCKED
                && orderNo != null
                && orderNo.equals(member.getOrderNo());
    }
}
