package com.mall.demo.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 订单按日统计的<b>契约快照</b>（对应表 {@code oms_order_daily_stat}）。
 *
 * <p><b>为什么需要它</b>：这个形态此前直接由持久化实体 {@code oms.domain.OrderDailyStat} 承担——
 * 于是 `/api/admin/stat/daily` 与 `StatOverviewVO.today` 把实体当成了对外契约，
 * 任何跨域使用（如后台统计）都会把"别人的表结构"钉死在调用方（架构闸门里的 B2）。
 * 换成契约快照后，JSON 字段与顺序<b>完全不变</b>（字段名与类型逐一对应），
 * 但"表结构"与"接口契约"解耦了：改表不必改契约，改契约不必动表。
 *
 * <p>迁移说明：与 {@code OrderDailyStat} 的字段一一对应，新增/删除字段时<b>两边都要改</b>，
 * 这正是把"实体即契约"改成"显式契约"的代价——有意为之。
 */
@Data
@Schema(description = "订单按日统计")
public class OrderDailyStatVO {

    private Long id;

    /** 统计日期 */
    private LocalDate statDate;

    /** 下单数（按订单创建时间） */
    private Integer orderCount;

    /** 支付笔数（按支付时间，pay_status >= 1） */
    private Integer paidCount;

    /** 支付金额（分） */
    private Long paidAmount;

    /** 退款完成笔数（按售后完成时间） */
    private Integer refundCount;

    /** 退款金额（分） */
    private Long refundAmount;

    private LocalDateTime updateTime;
}
