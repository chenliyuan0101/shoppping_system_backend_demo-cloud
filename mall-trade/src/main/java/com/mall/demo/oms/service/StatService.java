package com.mall.demo.oms.service;

import com.mall.demo.common.dto.OrderDailyStatVO;
import com.mall.demo.common.dto.StatOverviewVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 订单统计（派生数据，可随时重算）。
 *
 * <p>数据来源是 {@code oms_order} / {@code oms_refund} 的**实时重算**，不是"事件累加"：
 * MQ 事件只负责"触发重算"，所以重复消费、漏消费、甚至直接改库之后，只要调一次
 * {@link #refreshDay(LocalDate)} 就能追平——这与 ES 索引"可全量重建"的思路一致。
 */
public interface StatService {

    /** 重算某天（订单侧 + 售后侧），返回是否成功 */
    boolean refreshDay(LocalDate date);

    /** 重算今天 */
    boolean refreshToday();

    /**
     * 最近 N 天的日统计（含今天，按日期倒序）；没数据的日期不会出现在结果里。
     *
     * <p>返回<b>契约快照</b> {@link OrderDailyStatVO} 而不是持久化实体：本方法的结果会被管理端
     * 直接当响应体，返回实体等于把 {@code oms_order_daily_stat} 的<b>表结构钉成对外契约</b>
     * （P0 边界冻结要消灭的正是这种"实体即契约"）。字段与 JSON 形态保持不变。
     */
    List<OrderDailyStatVO> recentDays(int days);

    /** 概览：今天 + 近 7 天合计（后台首页用） */
    StatOverviewVO overview();
}
