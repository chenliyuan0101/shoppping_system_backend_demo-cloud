package com.mall.admin.task;

import com.mall.admin.service.AdminStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * <b>"对账任务真的会跑"</b>的端到端证据（启真 Spring 上下文 + 真调度器，只把 Service 换成替身）。
 *
 * <h2>为什么需要这一条（本批最容易做错、也最难发现的地方）</h2>
 * {@code @Scheduled} 在两种情况下**永远不会执行，而且不报任何错**：
 * <ol>
 *   <li>忘了在启动类上加 {@code @EnableScheduling}（{@code AdminStatusReconcileTaskTest} 用反射钉住了它）；</li>
 *   <li>属性没绑定上/写错了键名（例如写成 {@code status-reconcile-interval} 或把 enabled 拼错）——
 *       反射看不出来，只有"真跑一遍"才知道。</li>
 * </ol>
 * 而 P7 §2.5 的安全论证完全建立在这个任务会跑的前提上：**今天没有"禁用管理员"的入口**，
 * 如果对账不跑，被禁用的管理员在路由切换后仍然能用，`403 账号已被禁用` 也永远到不了。
 *
 * <p>做法：把间隔压到 200ms、初始延迟 0，注入 {@code @MockitoBean AdminStatusService}（**不碰真 Redis**，
 * 因此不会给环境留下任何键），然后断言"在 5 秒内至少被调用过一次"。
 * 这一条通过 ⇒ 注解、{@code @EnableScheduling}、属性占位符、调度线程池整条链都是通的。
 *
 * <p>⚠️ 本类**故意不继承** {@code AdminTestBase}：基类的 {@code @SpringBootTest(properties=...)}
 * 会把 {@code status-reconcile-enabled=false} 带进来（父子属性不合并），那正是本类要打开的东西。
 */
@SpringBootTest(properties = {
        "mall.admin.status-reconcile-enabled=true",
        "mall.admin.status-reconcile-interval-ms=200",
        "mall.admin.status-reconcile-initial-delay-ms=0",
        "mall.gateway.auth-token=test-gateway-token"
})
class AdminStatusReconcileSchedulingTest {

    @Autowired
    private AdminStatusReconcileTask adminStatusReconcileTask;

    @MockitoBean
    private AdminStatusService adminStatusService;

    @Test
    @DisplayName("🔴 对账任务在真上下文里**确实被调度执行**（@EnableScheduling + 属性绑定整条链通的）")
    void scheduledTaskActuallyRuns() {
        verify(adminStatusService, timeout(5_000).atLeastOnce()).reconcile();
    }

    @Test
    @DisplayName("开关读得到：本上下文里 enabled=true（false 时一行都不会跑，见 AdminStatusReconcileTaskTest）")
    void switchIsOnInThisContext() {
        org.assertj.core.api.Assertions.assertThat(adminStatusReconcileTask.enabled()).isTrue();
    }
}
