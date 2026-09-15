package com.mall.trade.oms.dto;

import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 后台订单列表项。
 */
@Data
public class AdminOrderListVO {

    private String orderNo;
    private Long memberId;
    private String memberName;
    private String memberPhone;
    private Integer orderStatus;
    private Integer payStatus;
    private String statusText;
    private Long payAmount;
    private Integer itemCount;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
}
