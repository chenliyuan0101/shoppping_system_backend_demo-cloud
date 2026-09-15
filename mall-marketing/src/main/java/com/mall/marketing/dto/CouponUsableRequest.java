package com.mall.marketing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import com.mall.common.support.MemberId;

/**
 * {@code POST /internal/v1/marketing/coupon/usable} 的请求体：算"这个会员在这个金额下能用哪些券"。
 *
 * <p>调用方是 trade 的结算页（批次 4 客户端化）。{@code goodsTotal} 必须传**当前购物车的商品总额**
 * （分），因为券门槛是"按本单金额"判定的——不传或传 0 会让满减券被误判成不可用。
 *
 * <p>⚠️ {@code memberId} 由调用方（trade）从**服务端上下文**填入，不是前端可填字段：
 * 这个端点的唯一门禁是 {@code X-Internal-Token}（{@code /internal/**} 到不了前端）。
 * 若哪天它被暴露成公开端点，"传任意 memberId 就能看别人的券"会立刻成立。
 */
@Data
public class CouponUsableRequest {

    @NotNull(message = "会员ID不能为空")
    private Long memberId;

    @NotNull(message = "商品金额不能为空")
    @PositiveOrZero(message = "商品金额不能为负")
    private Long goodsTotal;
}
