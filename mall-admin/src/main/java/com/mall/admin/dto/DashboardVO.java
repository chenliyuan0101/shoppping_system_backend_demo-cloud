package com.mall.admin.dto;

import lombok.Data;

import java.util.List;

/**
 * 后台看板数据（<b>C1 契约：逐字对齐单体 {@code com.mall.demo.admin.dto.DashboardVO}</b>）。
 *
 * <p>它是三个看板端点的 {@code data} 形状的宿主类；每个端点的 {@code data} 分别是
 * {@link Summary} / {@code List<TrendItem>} / {@code List<TopItem>}
 * ⇒ 对外 JSON 键路径集合是：
 * <pre>
 *   /summary : data.{todayOrderCount,todaySalesAmount,waitShipCount,refundPendingCount,onShelfProductCount,memberCount}
 *   /trend   : data[*].{date,orderCount,salesAmount}
 *   /top     : data[*].{spuId,title,mainImage,value}
 * </pre>
 * ⚠️ <b>P7 §3 的降级判据正是"这个键路径集合不变"</b>：任一依赖不可用时，
 * 缺的那部分回 0 / 空数组（**不是 null、不是 500**），键路径集合与正常时一致
 * （空数组会天然少掉"元素键"，这一点在 {@code AdminDashboardConcurrencyStubTest} 与
 * {@code P7-dashboard-member-report.md} 的降级矩阵里如实写明）。
 *
 * <p>⚠️ 六个 summary 字段与三个 trend/top 字段都是**原始类型**（{@code long}）：
 * 单体就是这么声明的，所以它们**永远序列化成数字、永远不会是 null**；
 * top 的 {@code mainImage} 在"商品已删除"时确实是 null（单体口径），这是**业务值**而不是降级。
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
