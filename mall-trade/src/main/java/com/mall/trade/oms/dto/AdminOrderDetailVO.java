package com.mall.trade.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 后台订单详情(含会员信息)。
 */
@Data
public class AdminOrderDetailVO {

    private String orderNo;
    private Long memberId;
    private String memberUsername;
    private String memberName;
    private String memberPhone;
    private Integer orderStatus;
    private Integer payStatus;
    private String statusText;
    private Long totalAmount;
    private Long freightAmount;
    private Long discountAmount;
    private Long payAmount;
    private String userRemark;
    private String receiverName;
    private String receiverPhone;
    private String receiverFullAddress;
    private String logisticsCompany;
    private String logisticsNo;
    private List<OrderDetailVO.OrderItemSnapshotVO> items;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime shipTime;
    private LocalDateTime finishTime;
}
