package com.mall.demo.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单主表，对应 oms_order(字段注释见《数据库设计文档.md》4.11)。
 * 状态：0待支付 1待发货 2待收货 3已完成 4已取消 5已关闭
 * 支付：0未支付 1已支付 2已全额退款
 */
@Data
@TableName("oms_order")
public class Order {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long memberId;

    private Integer orderStatus;

    private Integer payStatus;

    /** 一期固定 MOCK */
    private String payChannel;

    private LocalDateTime payTime;

    /** 1购物车结算 2立即购买 */
    private Integer source;

    private Long totalAmount;

    private Long freightAmount;

    private Long discountAmount;

    private Long payAmount;

    private Long couponId;

    private String userRemark;

    private String receiverName;

    private String receiverPhone;

    private String receiverFullAddress;

    private LocalDateTime payExpireTime;

    private String logisticsCompany;

    private String logisticsNo;

    private LocalDateTime shipTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime closeTime;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
