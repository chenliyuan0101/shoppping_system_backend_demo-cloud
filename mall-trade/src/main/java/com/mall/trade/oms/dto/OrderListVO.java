package com.mall.trade.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的订单列表项(含售后摘要：「退款/售后」标签页与状态标签都用它)。
 */
@Data
public class OrderListVO {

    private String orderNo;
    /** 0 待支付 / 1 待发货 / 2 待收货 / 3 已完成 / 4 已取消 / 5 已关闭 / 6 退款中 / 7 已退款 */
    private Integer orderStatus;
    private Integer payStatus;
    private Long payAmount;
    private Integer itemCount;
    private String firstTitle;
    private String firstImage;
    private String statusText;
    private LocalDateTime createTime;
    private LocalDateTime payExpireTime;

    // ---------- 售后摘要(有售后单时非空) ----------
    private String refundNo;
    private Integer refundType;
    private String refundTypeText;
    private Integer refundStatus;
    private String refundStatusText;
}
