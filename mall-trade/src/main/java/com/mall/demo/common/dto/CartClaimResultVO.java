package com.mall.demo.common.dto;

import java.util.List;

/**
 * 购物车结算闸门结果（P3 两阶段闸门的第一阶段返回值）。
 *
 * <p>{@code claimed=true} 表示这批明细**归本订单**——包含两种情况：
 * ① 本次真的领取成功；② 同一 {@code orderNo} 的重放（幂等：双击提交时第二次也返回 true，
 * 而不是骗人的"0 行 → 已被别人结算"）。
 *
 * <p>{@code items} 是领取到的明细快照（首次领取时即当时读到的条目；重放时是当时存下的快照）。
 * 字段形状与 {@code /internal/v1/user/cart/claim} 的响应逐字一致（跨服务契约快照）。
 */
public record CartClaimResultVO(boolean claimed, List<CartItemSnapshotVO> items) {

    /** 没领到：明细已被别的订单结算/不属于该会员 */
    public static CartClaimResultVO notClaimed() {
        return new CartClaimResultVO(false, List.of());
    }
}
