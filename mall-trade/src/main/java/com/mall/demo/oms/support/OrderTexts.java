package com.mall.demo.oms.support;

import com.mall.demo.common.constant.OrderStatus;

/**
 * 订单状态文案(含售后态)：0 待支付 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消 / 5 已关闭 / 6 退款中 / 7 已退款。
 * 取值定义见 {@link OrderStatus}。
 * 用户端「我的订单」、后台订单、售后详情共用，避免各处硬编码。
 */
public final class OrderTexts {

    private OrderTexts() {
    }

    public static String orderStatus(Integer status) {
        return switch (status == null ? -1 : status) {
            case OrderStatus.WAIT_PAY -> "待支付";
            case OrderStatus.WAIT_SHIP -> "待发货";
            case OrderStatus.WAIT_RECEIVE -> "待收货";
            case OrderStatus.FINISHED -> "已完成";
            case OrderStatus.CANCELED -> "已取消";
            case OrderStatus.CLOSED -> "已关闭";
            case OrderStatus.REFUNDING -> "退款中";
            case OrderStatus.REFUNDED -> "已退款";
            default -> "未知";
        };
    }
}
