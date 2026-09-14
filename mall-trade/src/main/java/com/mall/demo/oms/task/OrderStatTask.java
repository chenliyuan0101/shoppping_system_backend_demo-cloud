package com.mall.demo.oms.task;

import com.mall.demo.common.MallTime;
import com.mall.demo.oms.service.StatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 订单日统计的**兜底重算**任务。
 *
 * <p>为什么需要它：{@code oms_order_daily_stat} 是派生数据，平时由领域事件触发整行重算
 * （见 {@code OrderEventConsumer}）。但"事件触发"依赖当天**至少有一条事件**，于是有两处盖不住：
 * <ul>
 *   <li>某天一条事件都没有（例如停服、或那天确实没有支付/发货/退款）→ 那天不会有统计行，
 *       后台看板与趋势图那一格就是空的；</li>
 *   <li>统计行万一算错（重算时抛异常、或有人直接改过库），当天不一定还会被事件再触发一次。</li>
 * </ul>
 *
 * <p>每天 00:05 重算"昨天 + 今天"即可覆盖这两种情况。重算是**幂等**的（每次从源表整行重算，
 * 不是事件累加），所以重复执行安全，也不需要分布式锁。
 *
 * <p>可用 {@code mall.stat.refresh-cron} 覆盖执行时间；设为 {@code -} 即关闭本任务
 * （Spring 的 {@code Scheduled.CRON_DISABLED}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatTask {

    private final StatService statService;

    @Scheduled(cron = "${mall.stat.refresh-cron:0 5 0 * * ?}")
    public void refreshDaily() {
        LocalDate yesterday = MallTime.today().minusDays(1);
        try {
            boolean yesterdayOk = statService.refreshDay(yesterday);
            boolean todayOk = statService.refreshToday();
            if (yesterdayOk && todayOk) {
                log.info("日统计兜底重算完成: {} / {}", yesterday, MallTime.today());
            } else {
                log.warn("日统计兜底重算未全部成功: yesterday({})={} today={}", yesterday, yesterdayOk, todayOk);
            }
        } catch (Exception e) {
            // 兜底任务自己不能把调度线程打挂：失败就下一个周期再来（重算幂等，重试安全）
            log.error("日统计兜底重算执行失败: {}", yesterday, e);
        }
    }
}
