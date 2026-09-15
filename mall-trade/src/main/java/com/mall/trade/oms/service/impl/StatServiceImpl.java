package com.mall.trade.oms.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.support.MallTime;
import com.mall.common.support.PageKit;
import com.mall.trade.common.dto.OrderDailyStatVO;
import com.mall.trade.common.dto.StatOverviewVO;
import com.mall.trade.oms.domain.OrderDailyStat;
import com.mall.trade.oms.mapper.OrderDailyStatMapper;
import com.mall.trade.oms.service.StatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 订单统计实现：每条方法都是"从源表重算"，因此天然幂等、可重放。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatServiceImpl implements StatService {

    /** overview 里近 7 天的窗口 */
    private static final int WEEK_DAYS = 7;

    private final OrderDailyStatMapper statMapper;

    @Override
    @Transactional
    public boolean refreshDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        try {
            statMapper.refreshOrderSide(date);
            statMapper.refreshRefundSide(date);
            return true;
        } catch (Exception e) {
            // 不抛出：调用方(消费者/接口)据此决定"重试还是返回失败"；重算是幂等的，重试安全
            log.warn("订单统计重算失败 date={}: {}", date, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean refreshToday() {
        return refreshDay(MallTime.today());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderDailyStatVO> recentDays(int days) {
        long n = PageKit.size(days, 90);
        LocalDate from = MallTime.today().minusDays(n - 1L);
        return statMapper.selectList(new LambdaQueryWrapper<OrderDailyStat>()
                        .ge(OrderDailyStat::getStatDate, from)
                        .orderByDesc(OrderDailyStat::getStatDate))
                .stream()
                .map(StatServiceImpl::toVo)
                .toList();
    }

    /** 实体 → 契约快照（字段一一对应，因此对外 JSON 与改造前完全一致） */
    private static OrderDailyStatVO toVo(OrderDailyStat stat) {
        if (stat == null) {
            return null;   // overview.today 在"当天还没有统计行"时就是 null，语义与改造前一致
        }
        OrderDailyStatVO vo = new OrderDailyStatVO();
        vo.setId(stat.getId());
        vo.setStatDate(stat.getStatDate());
        vo.setOrderCount(stat.getOrderCount());
        vo.setPaidCount(stat.getPaidCount());
        vo.setPaidAmount(stat.getPaidAmount());
        vo.setRefundCount(stat.getRefundCount());
        vo.setRefundAmount(stat.getRefundAmount());
        vo.setUpdateTime(stat.getUpdateTime());
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public StatOverviewVO overview() {
        LocalDate today = MallTime.today();
        OrderDailyStat todayStat = statMapper.selectOne(new LambdaQueryWrapper<OrderDailyStat>()
                .eq(OrderDailyStat::getStatDate, today));
        List<OrderDailyStatVO> week = recentDays(WEEK_DAYS);

        StatOverviewVO data = new StatOverviewVO();
        data.setToday(toVo(todayStat));
        data.setTodayDate(today.toString());
        data.setWeekOrderCount(week.stream().mapToInt(s -> nz(s.getOrderCount())).sum());
        data.setWeekPaidCount(week.stream().mapToInt(s -> nz(s.getPaidCount())).sum());
        data.setWeekPaidAmount(week.stream().mapToLong(s -> nz(s.getPaidAmount())).sum());
        data.setWeekRefundCount(week.stream().mapToInt(s -> nz(s.getRefundCount())).sum());
        data.setWeekRefundAmount(week.stream().mapToLong(s -> nz(s.getRefundAmount())).sum());
        data.setWeekDays(WEEK_DAYS);
        return data;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private long nz(Long v) {
        return v == null ? 0L : v;
    }
}
