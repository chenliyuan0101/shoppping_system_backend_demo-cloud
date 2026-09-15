package com.mall.trade.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后单详情/列表项(用户与后台通用)。
 */
@Data
public class RefundVO {

    private Long id;
    private String refundNo;
    private String orderNo;
    private Integer refundType;
    private String typeText;
    private String reason;
    private String description;
    private List<String> images;
    private Long refundAmount;
    private Integer status;
    private String statusText;
    private String returnCompany;
    private String returnTrackingNo;
    private String auditRemark;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    /** 关联订单状态文本(便于展示) */
    private String orderStatusText;
}
