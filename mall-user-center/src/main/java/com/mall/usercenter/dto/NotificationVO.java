package com.mall.usercenter.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内消息展示对象。
 */
@Data
public class NotificationVO {

    private Long id;

    private String type;

    private String title;

    private String content;

    /** 业务单号（订单号/售后单号），前端可用来跳详情 */
    private String bizNo;

    private Integer isRead;

    private LocalDateTime createTime;
}
