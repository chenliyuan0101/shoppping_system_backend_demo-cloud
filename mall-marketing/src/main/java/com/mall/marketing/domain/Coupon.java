package com.mall.marketing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 优惠券模板，对应 {@code sms_coupon}。字段与单体 {@code com.mall.demo.sms.domain.Coupon} 一致
 * （表结构由 mysqldump 导出改写，见 db/01-mall_marketing-schema.sql）。
 */
@Data
@TableName("sms_coupon")
public class Coupon {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 1满减券(直减) */
    private Integer type;

    /** 门槛(分，0=无门槛) */
    private Long thresholdAmount;

    /** 减免金额(分) */
    private Long discountAmount;

    /** 发行量 NULL=不限 */
    private Integer totalCount;

    private Integer receivedCount;

    private Integer perMemberLimit;

    /** 1固定时间段 2领取后N天 */
    private Integer validType;

    private LocalDateTime validStartTime;

    private LocalDateTime validEndTime;

    private Integer validDays;

    /** 0启用 1停用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
