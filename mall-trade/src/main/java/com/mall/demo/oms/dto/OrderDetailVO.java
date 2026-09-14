package com.mall.demo.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单详情。
 */
@Data
public class OrderDetailVO {

    private String orderNo;
    private Integer orderStatus;
    private Integer payStatus;
    private String payChannel;
    private String sourceText;
    private Long totalAmount;
    private Long freightAmount;
    private Long discountAmount;
    private Long payAmount;
    private String userRemark;
    private String statusText;
    private AddressInfoVO address;
    private List<OrderItemSnapshotVO> items;
    private LocalDateTime payTime;
    private LocalDateTime shipTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    /** 关联售后单(有则展示售后进度与回寄信息) */
    private RefundVO refund;

    @Data
    public static class OrderItemSnapshotVO {
        private Long orderItemId;
        private Long spuId;
        private Long skuId;
        private String title;
        private String skuName;
        private String image;
        private Long price;
        private Integer quantity;
    }
}
