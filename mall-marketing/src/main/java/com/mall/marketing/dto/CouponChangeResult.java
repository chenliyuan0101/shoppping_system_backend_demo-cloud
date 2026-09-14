package com.mall.marketing.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code use} / {@code unlock} 的响应数据：{@code {"changed": true|false}}，**两个端点同形**。
 *
 * <p>语义（方案 §4.3 的幂等口径）：
 * <ul>
 *   <li>{@code true} = 券现在处于该操作期望的终态——可能是**这次真的改了**
 *       （{@code 3→1} / {@code 3→0}），也可能是**本来就已是终态**（幂等命中）；</li>
 *   <li>{@code false} = 这张券不是本单锁的（被别的单锁着、已被核销、或已解锁给别人用掉）。</li>
 * </ul>
 * 之所以把"改了"与"本来就是这样"合并成 true：调用方（trade）要的答案是
 * "这张券的状态现在对不对"，而不是"你刚才那一下有没有写库"。
 *
 * <p>⚠️ {@code false} **不是**异常：支付回调里拿 {@code use=false} 只记日志（**不得让支付失败**，
 * 钱已收），取消/超时路径拿 {@code unlock=false} 也只记日志并交给每日对账（批次 5）。
 * 若把它做成 500/409，就会出现"用户钱付了、订单却因券报错而失败"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponChangeResult {

    private boolean changed;

    public static CouponChangeResult of(boolean changed) {
        return new CouponChangeResult(changed);
    }
}
