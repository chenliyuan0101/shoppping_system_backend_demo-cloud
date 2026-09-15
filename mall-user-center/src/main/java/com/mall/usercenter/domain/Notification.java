package com.mall.usercenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 站内消息，对应 ums_notification。
 *
 * <p>由 MQ 领域事件驱动生成（支付成功 / 已发货 / 退款到账），
 * 唯一键 {@code (member_id, type, biz_no)} 保证"同一条业务事件只产生一条消息"（at-least-once 消费下的幂等）。
 */
@Data
@TableName("ums_notification")
public class Notification {

    public static final String TYPE_ORDER_PAID = "ORDER_PAID";
    public static final String TYPE_ORDER_SHIPPED = "ORDER_SHIPPED";
    public static final String TYPE_REFUND_SETTLED = "REFUND_SETTLED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private String type;

    private String title;

    private String content;

    /** 业务单号（订单号/售后单号），与 type 一起做幂等去重 */
    private String bizNo;

    private Integer isRead;

    private LocalDateTime readTime;

    private LocalDateTime createTime;
}
