package com.mall.admin.support.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单趋势图上的一个点：单体 {@code com.mall.demo.common.dto.OrderTrendPointVO} 的契约快照
 * （也是 {@code GET /internal/v1/stat/trend} 的元素形状）。
 *
 * <p>契约：序列**连续且补零**（没有下单的日期也在，值为 0），按日期升序——
 * 补零是订单域的数据口径，BFF 直接画图。
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
