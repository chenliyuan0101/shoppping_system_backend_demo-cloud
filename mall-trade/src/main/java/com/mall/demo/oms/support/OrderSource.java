package com.mall.demo.oms.support;

/**
 * 订单来源 {@code oms_order.source}：1 购物车结算 / 2 立即购买。
 *
 * <p>入参侧用字符串 {@code CART} / {@code BUY_NOW} 表达，落库前转换成本类常量。
 */
public final class OrderSource {

    public static final int CART = 1;
    public static final int BUY_NOW = 2;

    private OrderSource() {
    }
}
