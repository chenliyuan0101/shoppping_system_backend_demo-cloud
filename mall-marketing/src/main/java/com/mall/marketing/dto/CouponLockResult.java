package com.mall.marketing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code lock} 的响应数据：{@code {"locked": true|false}}。
 *
 * <p>用**对象**而不是裸 boolean，是为了让契约以后能加字段（如 {@code lockedAt}）而不破坏调用方；
 * 也与既有内部接口"data 是对象"的形状一致。
 *
 * <p>语义：{@code true} = 券现在**确实**被本单锁定（含"本来就已经被本单锁定"的幂等情形）；
 * {@code false} = 没锁上。⚠️ 但"没锁上"的两类原因**不在这里区分**：
 * 参数缺失/非法是 400，被别人锁/已核销/已过期是 **409「优惠券已被使用或失效」**，
 * 两者都会以非 0 的 {@code code} 返回——{@code data} 里的 false 只出现在
 * "参数合法但服务端判定无法锁定"的极窄情形（见 {@code CouponCommandServiceImpl#lock}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponLockResult {

    private boolean locked;

    public static CouponLockResult of(boolean locked) {
        return new CouponLockResult(locked);
    }
}
