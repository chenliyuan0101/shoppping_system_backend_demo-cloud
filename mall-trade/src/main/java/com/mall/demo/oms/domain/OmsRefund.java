package com.mall.demo.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 售后单，对应 oms_refund。
 * refund_type: 1仅退款 2退货退款
 * status: 0待处理 1已同意处理中 2已完成(模拟退款成功) 3已拒绝 4已取消(用户)
 */
@Data
@TableName("oms_refund")
public class OmsRefund {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String refundNo;

    private String orderNo;

    private Long orderItemId;

    private Long memberId;

    private Integer refundType;

    private String reason;

    private String description;

    /** json 凭证图数组 */
    private String images;

    private Long refundAmount;

    private Integer status;

    private String returnCompany;

    private String returnTrackingNo;

    private LocalDateTime receivedTime;

    private Long auditBy;

    private LocalDateTime auditTime;

    private String auditRemark;

    private LocalDateTime finishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
