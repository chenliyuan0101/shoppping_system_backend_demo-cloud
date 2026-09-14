package com.mall.demo.oms.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 结算预览 / 创建订单请求。
 */
@Data
@Schema(description = "下单请求(CART 与 BUY_NOW 二选一)")
public class OrderCreateRequest {

    @Schema(description = "来源：CART=购物车结算 / BUY_NOW=立即购买", example = "CART")
    private String source;

    @Schema(description = "购物车条目ID(勾选项，source=CART 必填)", example = "[1,2]")
    private List<Long> cartItemIds;

    @Schema(description = "立即购买项(source=BUY_NOW 必填)")
    private OrderBuyNow buyNow;

    @Schema(description = "收货地址ID", example = "1")
    private Long addressId;

    @Schema(description = "使用的优惠券(用户券ID，可空；见 /api/coupon/mine)", example = "1")
    private Long couponId;

    @Schema(description = "用户备注(选填)", example = "请放门口")
    private String userRemark;
}
