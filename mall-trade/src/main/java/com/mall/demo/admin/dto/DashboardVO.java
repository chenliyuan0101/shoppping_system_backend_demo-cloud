package com.mall.demo.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 后台看板数据。
 */
@Data
public class DashboardVO {

    private Summary summary;

    /** 近 N 日趋势(按日期升序) */
    private List<TrendItem> trend;

    /** 榜单：type=sales 时按销量，type=amount 时按销售额 */
    private List<TopItem> top;

    @Data
    public static class Summary {
        private long todayOrderCount;
        private long todaySalesAmount;
        private long waitShipCount;
        private long refundPendingCount;
        private long onShelfProductCount;
        private long memberCount;
    }

    @Data
    public static class TrendItem {
        private String date;
        private long orderCount;
        /** 当日已支付订单销售额(分) */
        private long salesAmount;
    }

    @Data
    public static class TopItem {
        private Long spuId;
        private String title;
        private String mainImage;
        /** 销量或销售额(分) */
        private long value;
    }
}
