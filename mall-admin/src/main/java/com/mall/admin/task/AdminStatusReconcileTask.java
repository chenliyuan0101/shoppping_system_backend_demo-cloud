package com.mall.admin.task;

import com.mall.admin.service.AdminStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 管理端状态缓存对账任务（P7 §2.5 的硬前置）。
 *
 * <h2>为什么必须有它（本批最重要的一条）</h2>
 * 今天**没有任何"禁用管理员"的入口** —— 禁用是**直接改库**（{@code UPDATE sys_user SET status = 0}）。
 * 因此只靠"改状态时写缓存"，缓存会永远停在登录时写的 {@code status=1}：
 * <pre>
 *   网关读 mall:cache:admin:status:{id} ⇒ 看到 1 ⇒ 放行
 *   ⇒ `403 账号已被禁用` 这条文案在路由切换之后**根本到不了**
 *   ⇒ 被禁用的管理员还能继续调后台接口（单体的 AdminAuthInterceptor 已经不在那条链路上）
 * </pre>
 * 所以本任务每 {@code mall.admin.status-reconcile-interval-ms}（默认 **10 秒**）读一遍
 * {@code sys_user.status}，把缓存纠正过来，并在"启用→禁用"跃迁时 bump 令牌版本号。
 *
 * <h2>为什么是定时轮询而不是"库变更通知"</h2>
 * 因为变更**根本不经过本服务**（是值班人员直接改库），没有任何事件可订阅。
 * 轮询是唯一能覆盖"绕过应用改库"这种操作形态的机制；10 秒的延迟对"禁用管理员"这个场景可接受
 * （对比会员侧的 P3 也是同一思路：缓存 + 版本号 + 兜底对账）。
 *
 * <h2>fail-open</h2>
 * 整个方法体被 try/catch 包住：Redis/DB 故障只记 {@code log.warn}，
 * **绝不让定时线程抛异常**（抛出去只会让下一轮更晚跑，问题依旧存在）。
 * Redis 不可用时 {@code CacheService} 自己会退避降级，本任务空转即可。
 *
 * <h2>测试期</h2>
 * 测试用 {@code mall.admin.status-reconcile-enabled=false} 关掉调度，改为**直接调用服务**
 * （{@code AdminStatusService.reconcile()}）—— 断言需要确定性，不能和定时器抢时序。
 * 开关本身的语义（关掉就不跑、异常不外抛）由 {@code AdminStatusReconcileTaskTest} 单测覆盖。
 * ⚠️ 生产默认 **true**（见 application.yaml），这点在报告里点名。
 */
@Slf4j
@Component
public class AdminStatusReconcileTask {

    private final AdminStatusService adminStatusService;
    private final boolean enabled;

    public AdminStatusReconcileTask(AdminStatusService adminStatusService,
                                    @Value("${mall.admin.status-reconcile-enabled:true}") boolean enabled) {
        this.adminStatusService = adminStatusService;
        this.enabled = enabled;
        log.info("管理端状态缓存对账任务: enabled={}（false=只在对账被显式调用时生效，测试用）", enabled);
    }

    /**
     * 固定间隔对账（上一轮结束到下一轮开始，避免堆积）。
     *
     * <p>⚠️ 没有 {@code @EnableScheduling}（见 {@code AdminApplication}）时本方法**永远不会执行，而且不报错**
     * —— 那正是"缓存永远是旧的"这类静默失效的形状（search/marketing 都为此留过注释）。
     */
    @Scheduled(fixedDelayString = "${mall.admin.status-reconcile-interval-ms:10000}",
            initialDelayString = "${mall.admin.status-reconcile-initial-delay-ms:10000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            int fixed = adminStatusService.reconcile();
            if (fixed > 0) {
                log.warn("管理端状态缓存对账完成：本轮修正/补写 {} 条（详情见上方的逐条日志）", fixed);
            } else if (log.isDebugEnabled()) {
                log.debug("管理端状态缓存对账完成：无差异");
            }
        } catch (Exception e) {
            // fail-open：对账失败不影响任何请求；下一轮（默认 10s 后）会重试
            log.warn("管理端状态缓存对账失败（fail-open，下一轮重试）: {}", e.getMessage(), e);
        }
    }

    /** 开关状态（自检/运维用） */
    public boolean enabled() {
        return enabled;
    }
}
