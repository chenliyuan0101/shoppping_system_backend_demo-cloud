package com.mall.admin.task;

import com.mall.admin.AdminApplication;
import com.mall.admin.service.AdminStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账任务（{@code AdminStatusReconcileTask}）的**纯单测**（不启 Spring 上下文）。
 *
 * <p>它覆盖三件在真库套件里覆盖不到的事：
 * <ol>
 *   <li><b>开关</b>：{@code mall.admin.status-reconcile-enabled=false} 时什么都不做（测试期就靠它保证确定性）；</li>
 *   <li><b>fail-open</b>：对账抛异常时**绝不外抛**（否则定时线程会带着异常结束，而问题依旧存在）；</li>
 *   <li><b>接线</b>：{@code @Scheduled} 的表达式与默认间隔、以及 {@code AdminApplication} 上的
 *       {@code @EnableScheduling} —— 少了那个注解，{@code @Scheduled} 方法
 *       **永远不会执行而且不报错**（"缓存永远是旧的"这类静默失效的形状）。</li>
 * </ol>
 */
class AdminStatusReconcileTaskTest {

    @Test
    @DisplayName("开关打开：调用 AdminStatusService.reconcile()")
    void enabled_invokesReconcile() {
        AdminStatusService service = mock(AdminStatusService.class);
        when(service.reconcile()).thenReturn(2);

        AdminStatusReconcileTask task = new AdminStatusReconcileTask(service, true);
        task.reconcile();

        assertThat(task.enabled()).isTrue();
        verify(service).reconcile();
    }

    @Test
    @DisplayName("开关关闭：一次都不调用（测试期靠它避免与用例抢时序）")
    void disabled_doesNotInvokeReconcile() {
        AdminStatusService service = mock(AdminStatusService.class);

        AdminStatusReconcileTask task = new AdminStatusReconcileTask(service, false);
        task.reconcile();

        assertThat(task.enabled()).isFalse();
        verify(service, never()).reconcile();
    }

    @Test
    @DisplayName("fail-open：对账抛异常时**不外抛**（Redis/DB 抖动不该让定时线程挂掉）")
    void exceptionIsSwallowed() {
        AdminStatusService service = mock(AdminStatusService.class);
        when(service.reconcile()).thenThrow(new IllegalStateException("Redis 不可用"));

        AdminStatusReconcileTask task = new AdminStatusReconcileTask(service, true);

        assertThatCode(task::reconcile).doesNotThrowAnyException();
        verify(service).reconcile();
    }

    @Test
    @DisplayName("接线：@Scheduled 用配置项（默认 10s，可调）+ AdminApplication 上有 @EnableScheduling")
    void scheduledWiring_isPresent() throws Exception {
        Method method = AdminStatusReconcileTask.class.getMethod("reconcile");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).as("@Scheduled 必须挂在 reconcile() 上，否则对账根本不会跑").isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${mall.admin.status-reconcile-interval-ms:10000}");
        assertThat(scheduled.initialDelayString()).isEqualTo("${mall.admin.status-reconcile-initial-delay-ms:10000}");
        assertThat(scheduled.cron()).isEmpty();

        // 缺 @EnableScheduling 时 @Scheduled 静默失效 —— 用反射把它钉死
        assertThat(AdminApplication.class.getAnnotation(EnableScheduling.class))
                .as("AdminApplication 必须有 @EnableScheduling，否则对账任务永远不会执行且不报错")
                .isNotNull();
    }
}
