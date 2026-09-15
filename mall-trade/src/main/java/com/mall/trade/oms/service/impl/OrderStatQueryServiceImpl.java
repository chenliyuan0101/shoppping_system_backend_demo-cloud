package com.mall.trade.oms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.support.MallTime;
import com.mall.common.support.PageKit;
import com.mall.trade.common.constant.OrderPayStatus;
import com.mall.trade.common.constant.OrderStatus;
import com.mall.trade.common.constant.RefundStatus;
import com.mall.trade.common.dto.MemberOrderBriefVO;
import com.mall.trade.common.dto.OrderSummaryVO;
import com.mall.trade.common.dto.OrderTrendPointVO;
import com.mall.trade.common.dto.SpuAmountVO;
import com.mall.trade.oms.domain.OmsRefund;
import com.mall.trade.oms.domain.Order;
import com.mall.trade.oms.mapper.OrderItemMapper;
import com.mall.trade.oms.mapper.OrderMapper;
import com.mall.trade.oms.mapper.RefundMapper;
import com.mall.trade.oms.service.OrderStatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.mall.common.support.MemberId;

/**
 * 看板统计契约实现：计数/求和/分组/排序全部在 DB 完成，Java 侧只做"补零对齐"这类轻组装。
 *
 * <p>本类是从 {@code admin.service.impl.AdminDashboardServiceImpl} 里<b>原样搬过来</b>的订单口径聚合，
 * 行为（含 SQL 条件、日期分组、Top 排序）完全不变；差别只在于：以前 admin 直连订单表，
 * 现在订单表只能由本域读，对外只暴露 DTO。
 */
@Service
@RequiredArgsConstructor
public class OrderStatQueryServiceImpl implements OrderStatQueryService {

    /** 趋势天数上限（与改造前 admin 侧的口径一致） */
    private static final int MAX_TREND_DAYS = 30;
    /** 榜单条数上限（同上） */
    private static final int MAX_TOP_LIMIT = 20;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final RefundMapper refundMapper;

    @Override
    @Transactional(readOnly = true)
    public OrderSummaryVO summary() {
        LocalDateTime todayStart = MallTime.today().atStartOfDay();
        LocalDateTime tomorrow = todayStart.plusDays(1);

        long todayOrders = nz(orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .ge(Order::getCreateTime, todayStart).lt(Order::getCreateTime, tomorrow)));
        long todaySales = nz(orderMapper.sumPaidBetween(todayStart, tomorrow));
        long waitShip = nz(orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, OrderStatus.WAIT_SHIP)));
        long refundPending = nz(refundMapper.selectCount(new LambdaQueryWrapper<OmsRefund>()
                .eq(OmsRefund::getStatus, RefundStatus.PENDING)));

        return new OrderSummaryVO(todayOrders, todaySales, waitShip, refundPending);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderTrendPointVO> trend(int days) {
        int n = (int) PageKit.size(days, MAX_TREND_DAYS);
        LocalDateTime start = MallTime.today().minusDays(n - 1L).atStartOfDay();

        // 按日分组在 DB 完成，只返回有数据的日期
        Map<String, Long> orderByDay = toLongMap(orderMapper.countOrdersGroupByDay(start), "cnt");
        Map<String, Long> salesByDay = toLongMap(orderMapper.sumPaidGroupByDay(start), "amt");

        // Java 只负责"补零对齐"输出连续 N 天
        List<OrderTrendPointVO> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String key = start.toLocalDate().plusDays(i).toString();
            list.add(new OrderTrendPointVO(key,
                    orderByDay.getOrDefault(key, 0L),
                    salesByDay.getOrDefault(key, 0L)));
        }
        return list;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpuAmountVO> topPaidAmountBySpu(int limit) {
        int n = (int) PageKit.size(limit, MAX_TOP_LIMIT);
        return orderItemMapper.sumPaidAmountBySpu(n).stream()
                .map(row -> new SpuAmountVO(
                        ((Number) rowValue(row, "spuId")).longValue(),
                        ((Number) rowValue(row, "amount")).longValue()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MemberOrderBriefVO memberOrderBrief(Long memberId) {
        if (memberId == null) {
            return new MemberOrderBriefVO(0, 0);
        }
        long orderCount = nz(orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getMemberId, memberId)));
        // 累计实付只算 pay_status=1（已支付）；已全额退款(2)不计入——与改造前口径一致
        List<Object> paidList = orderMapper.selectObjs(new LambdaQueryWrapper<Order>()
                .select(Order::getPayAmount)
                .eq(Order::getMemberId, memberId)
                .eq(Order::getPayStatus, OrderPayStatus.PAID));
        long paidAmount = paidList.stream().filter(Objects::nonNull)
                .mapToLong(o -> ((Number) o).longValue()).sum();
        return new MemberOrderBriefVO(orderCount, paidAmount);
    }

    /**
     * 批量版（P7）：**一条** 聚合 SQL 取回一页会员的订单口径摘要。
     *
     * <p>语义与 {@link #memberOrderBrief(Long)} 完全一致，只是把"每会员两次查询"换成
     * "一页一次 GROUP BY"（后台会员列表的 §4 无 N+1 判据）。
     * 没有订单的会员在 SQL 结果里不出现，这里**显式补 0**——因为单条口径对无订单会员返回的就是
     * {@code (0, 0)}，两个方法对同一个会员必须给出同一个答案（否则"列表显示空白、详情显示 0"
     * 这种不一致会很难解释）。
     */
    @Override
    @Transactional(readOnly = true)
    public Map<Long, MemberOrderBriefVO> memberOrderBriefs(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = memberIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, MemberOrderBriefVO> fromDb = new HashMap<>();
        for (Map<String, Object> row : orderMapper.memberOrderBriefs(ids)) {
            Object id = rowValue(row, "memberId");
            if (id == null) {
                continue;
            }
            fromDb.put(((Number) id).longValue(), new MemberOrderBriefVO(
                    numberOrZero(rowValue(row, "cnt")),
                    numberOrZero(rowValue(row, "paid"))));
        }
        Map<Long, MemberOrderBriefVO> result = new LinkedHashMap<>();
        for (Long id : ids) {
            result.put(id, fromDb.getOrDefault(id, new MemberOrderBriefVO(0, 0)));
        }
        return result;
    }

    private static Map<String, Long> toLongMap(List<Map<String, Object>> rows, String valueKey) {
        Map<String, Long> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object day = rowValue(row, "d");
            if (day == null) {
                continue;
            }
            map.put(String.valueOf(day), ((Number) rowValue(row, valueKey)).longValue());
        }
        return map;
    }

    /** MySQL 返回的列别名可能大小写不同，做不区分大小写的取值 */
    private static Object rowValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    /** 聚合列取值（可能为 null / 可能不是 Number）→ long，异常一律按 0（聚合列不该让整个看板/列表 500） */
    private static long numberOrZero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
