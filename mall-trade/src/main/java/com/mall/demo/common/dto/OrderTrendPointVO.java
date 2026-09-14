package com.mall.demo.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单趋势图上的一个点（域间契约）：某一天的下单数与销售额。
 *
 * <p>契约约定：调用方拿到的序列是<b>连续且补零</b>的（没有下单的日期也在，
 * {@code orderCount}/{@code salesAmount} 为 0），按日期升序——趋势图的"补零对齐"是数据口径，
 * 放在订单域内实现，调用方直接画图即可。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTrendPointVO {

    /** 日期 yyyy-MM-dd */
    private String date;

    private long orderCount;

    /** 当日已支付销售额（分） */
    private long salesAmount;
}
