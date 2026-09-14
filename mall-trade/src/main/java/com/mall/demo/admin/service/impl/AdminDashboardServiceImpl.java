package com.mall.demo.admin.service.impl;

import com.mall.demo.admin.dto.DashboardVO;
import com.mall.demo.admin.service.AdminDashboardService;
import com.mall.demo.common.contract.MemberQueryService;
import com.mall.demo.common.dto.OrderSummaryVO;
import com.mall.demo.common.dto.SpuAmountVO;
import com.mall.demo.common.dto.SpuSnapshotVO;
import com.mall.demo.oms.service.OrderStatQueryService;
import com.mall.demo.pms.service.ProductQueryService;
import com.mall.demo.pms.service.ProductStatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 看板统计：向三个域各取"自己那部分"，在 admin 侧组装成看板视图。
 *
 * <p><b>P0 边界冻结</b>：本类不再直连 {@code oms_order}/{@code oms_refund}/{@code pms_spu}/{@code ums_member}
 * （原先 7 条跨域直连，其中 4 张表 + 3 个实体）。统计口径与排序位置未变——
 * 计数/求和/分组/排序仍在各域的 DB 里完成，本类只做 DTO → 视图对象的搬运与兜底。
 *
 * <p>拆服务后的注意点（§4.6）：本类是典型的 BFF 聚合点，届时这几段应当<b>并发</b>发起
 * （{@code CompletableFuture}）并各自设短超时；任一路失败时该部分返回 0/空，
 * 保持 HTTP 200 与响应结构不变，而不是整体 5xx。
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {

    private final OrderStatQueryService orderStatQueryService;
    private final ProductStatQueryService productStatQueryService;
    private final ProductQueryService productQueryService;
    private final MemberQueryService memberQueryService;

    @Override
    @Transactional(readOnly = true)
    public DashboardVO.Summary summary() {
        OrderSummaryVO order = orderStatQueryService.summary();

        DashboardVO.Summary s = new DashboardVO.Summary();
        s.setTodayOrderCount(order.getTodayOrderCount());
        s.setTodaySalesAmount(order.getTodaySalesAmount());
        s.setWaitShipCount(order.getWaitShipCount());
        s.setRefundPendingCount(order.getRefundPendingCount());
        s.setOnShelfProductCount(productStatQueryService.countEnabled());
        s.setMemberCount(memberQueryService.count());
        return s;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DashboardVO.TrendItem> trend(int days) {
        return orderStatQueryService.trend(days).stream().map(point -> {
            DashboardVO.TrendItem item = new DashboardVO.TrendItem();
            item.setDate(point.getDate());
            item.setOrderCount(point.getOrderCount());
            item.setSalesAmount(point.getSalesAmount());
            return item;
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DashboardVO.TopItem> top(String type, int limit) {
        return "amount".equalsIgnoreCase(type) ? topByAmount(limit) : topBySales(limit);
    }

    /** 销量榜：商品域已经按销量倒序取好前 N */
    private List<DashboardVO.TopItem> topBySales(int limit) {
        return productStatQueryService.topBySales(limit).stream().map(spu -> {
            DashboardVO.TopItem item = new DashboardVO.TopItem();
            item.setSpuId(spu.getId());
            item.setTitle(spu.getTitle());
            item.setMainImage(spu.getMainImage());
            item.setValue(spu.getSales() == null ? 0 : spu.getSales());
            return item;
        }).toList();
    }

    /** 销售额榜：订单域给"spuId + 金额"的有序列表，商品域补标题/主图 */
    private List<DashboardVO.TopItem> topByAmount(int limit) {
        List<SpuAmountVO> rows = orderStatQueryService.topPaidAmountBySpu(limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SpuSnapshotVO> spuMap = productQueryService
                .spus(rows.stream().map(SpuAmountVO::getSpuId).toList())
                .stream().collect(Collectors.toMap(SpuSnapshotVO::getId, s -> s));
        return rows.stream().map(row -> {
            SpuSnapshotVO spu = spuMap.get(row.getSpuId());
            DashboardVO.TopItem item = new DashboardVO.TopItem();
            item.setSpuId(row.getSpuId());
            item.setTitle(spu == null ? "商品已删除" : spu.getTitle());
            item.setMainImage(spu == null ? null : spu.getMainImage());
            item.setValue(row.getAmount());
            return item;
        }).toList();
    }
}
