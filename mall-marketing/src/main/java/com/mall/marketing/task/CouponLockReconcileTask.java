package com.mall.marketing.task;

import com.mall.marketing.service.CouponCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>券锁定对账</b>（P5 步骤 E）：扫"锁太久"的券并解锁。
 *
 * <h2>为什么必须有这个定时任务（而不是"靠 unlock 就够了"）</h2>
 * {@code unlock} 由**调用方在关单时主动发起**：下单事务回滚补偿、会员取消、超时关单、后台关单。
 * 这四条路径之外还有第四种情况——**它们全都没发生，或者发生了但没通知到**：
 * <ul>
 *   <li>{@code order.closed} 事件丢了（MQ 抖动、消费者当时不在线、DLQ 里躺着没人重投）；</li>
 *   <li>服务重启正好打断那次关单；</li>
 *   <li>有人直接改库/改数据把订单关掉了；</li>
 *   <li>历史遗留：加 {@code lock_time} 列之前就锁着的行。</li>
 * </ul>
 * 这些情况下那张券**没有任何调用方会再来解它**，永远停在 {@code LOCKED(3)}——
 * 对外投影成"已使用"，用户既用不了也看不见（比旧实现"直接烧成 USED"更隐蔽：旧实现至少状态是一致的）。
 * 对账是**最后一道防线**：它不依赖任何人通知，只按"锁了多久"判断。
 *
 * <h2>阈值与开关</h2>
 * <ul>
 *   <li>{@code mall.marketing.stuck-lock-hours}（默认 <b>2 小时</b>）：必须 **大于支付超时**
 *       （单体 {@code mall.order.pay-timeout-minutes} 默认 30 分钟），否则会误解锁用户
 *       "还在待支付窗口内正常锁着"的券，等他支付时 {@code use} 就会失败；</li>
 *   <li>{@code mall.marketing.reconcile.enabled}（默认 true）、
 *       {@code mall.marketing.reconcile-cron}（默认每天 00:05）、
 *       {@code mall.marketing.reconcile-batch-size}（默认 200）。</li>
 * </ul>
 *
 * <h2>两个刻意的写法</h2>
 * <ol>
 *   <li><b>cron 走配置</b>：默认每天一次（券对时间不敏感，一天足够），但**验证时必须能调快**
 *       ——我用 {@code --mall.marketing.reconcile-cron="*&#47;20 * * * * *"} 起服务来实测它真的会跑。
 *       如果 cron 写死在注解里，"任务到底跑没跑"就只能等到半夜才知道；</li>
 *   <li><b>整个方法包 try/catch + log.error</b>：定时任务里抛异常会污染调度线程，
 *       后续轮次可能就不再执行（而且没人会看到）。这与单体 {@code OrderTimeoutTask} 的口径一致。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponLockReconcileTask {

    private final CouponCommandService couponCommandService;

    @Value("${mall.marketing.reconcile.enabled:true}")
    private boolean enabled;

    @Value("${mall.marketing.reconcile-batch-size:200}")
    private int batchSize;

    /** 每日对账（默认 00:05；验证时用命令行覆盖 cron 调快） */
    @Scheduled(cron = "${mall.marketing.reconcile-cron:0 5 0 * * ?}")
    public void reconcileStuckLocks() {
        if (!enabled) {
            log.debug("券锁定对账已关闭(mall.marketing.reconcile.enabled=false)，跳过");
            return;
        }
        try {
            int unlocked = couponCommandService.unlockStuckLocks(batchSize);
            if (unlocked > 0) {
                log.warn("券锁定对账完成：解锁 {} 张（详见同一轮的逐行 WARN）", unlocked);
            } else {
                log.debug("券锁定对账完成：没有需要解锁的券");
            }
        } catch (Exception e) {
            // 不能往外抛：调度线程被异常打断后，后续轮次可能不再执行，且没人会注意到
            log.error("券锁定对账执行失败（下一轮会重试）", e);
        }
    }
}
