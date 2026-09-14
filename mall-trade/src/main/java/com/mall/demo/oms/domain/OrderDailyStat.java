package com.mall.demo.oms.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单按日统计，对应 oms_order_daily_stat。
 *
 * <p>**派生数据**：不靠"事件累加"，而是每次由 {@code StatService#refreshDay} 用一条
 * `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE` 从 `oms_order` / `oms_refund` 重算，
 * 所以重复消费、漏消费、手工改库都能靠重算追平（幂等）。
 */
@Data
@TableName("oms_order_daily_stat")
public class OrderDailyStat {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate statDate;

    /** 下单数（按订单创建时间） */
    private Integer orderCount;

    /** 支付笔数（按支付时间，pay_status >= 1） */
    private Integer paidCount;

    /** 支付金额（分） */
    private Long paidAmount;

    /** 退款完成笔数（按售后完成时间，status = 2） */
    private Integer refundCount;

    /** 退款金额（分） */
    private Long refundAmount;

    private LocalDateTime updateTime;
}
