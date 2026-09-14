package com.mall.demo.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 订单概览(今天 + 近 7 天合计)，后台首页用。
 *
 * <p>字段名与 {@code StatServiceImpl#overview()} 原来那个 Map 的键一一对应。
 *
 * <p>已上移为<b>共享契约 DTO</b>（原在 {@code oms.dto}）：它由 oms 产出、被 admin 消费，
 * 是"两个域之间的契约"而不是某个域的私有 DTO。
 * 其中 {@link #today} 用契约快照 {@link OrderDailyStatVO} 而不是持久化实体，
 * 避免把 {@code oms_order_daily_stat} 的表结构钉成对外契约。
 */
@Data
@Schema(description = "订单概览(今天 + 近 7 天合计)")
public class StatOverviewVO {

    /** 今天的日统计行；当天还没有统计行时为 null(原 Map 同样是 put(null)) */
    private OrderDailyStatVO today;

    /** 今天日期 yyyy-MM-dd */
    private String todayDate;

    /** 近 7 天下单数合计 */
    private int weekOrderCount;

    /** 近 7 天支付笔数合计 */
    private int weekPaidCount;

    /** 近 7 天支付金额合计(分) */
    private long weekPaidAmount;

    /** 近 7 天退款笔数合计 */
    private int weekRefundCount;

    /** 近 7 天退款金额合计(分) */
    private long weekRefundAmount;

    /** 统计窗口天数(7) */
    private int weekDays;
}
