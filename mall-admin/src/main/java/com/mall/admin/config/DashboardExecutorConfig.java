package com.mall.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 看板并行聚合用的线程池（P7 §3：各域统计必须**并行**发起，总耗时接近最慢的那一个）。
 *
 * <h2>为什么单独一个池，而不是 {@code CompletableFuture.supplyAsync} 的公共 ForkJoinPool</h2>
 * <ul>
 *   <li>公共池是所有代码共用的（连 {@code parallelStream} 也在里面）：看板一次请求占 3 个线程，
 *       若外部依赖都慢，这些线程会长时间占着公共池 —— 影响的是"跟我无关的别人的并行度"；</li>
 *   <li>这个池的**线程名**（{@code dashboard-agg-*}）让"看板在并发拉数"这件事在日志里一眼可见
 *       （证据性：降级/超时排查时能立刻区分"是我的线程池满了"还是"下游慢"）。</li>
 * </ul>
 *
 * <p>池大小默认 8：单次看板请求最多 3 个下游（trade/product/user），8 允许 2~3 个并发请求同时聚合；
 * 下游调用本身有 300ms/2500ms 的连接/读超时兜底，因此不需要无界队列或大池
 * （{@link Executors#newFixedThreadPool} 用的是无界队列：线程池满时**排队**而不是拒绝，
 * 对看板这种"慢一点也要有答案"的读路径正合适；真正的上界由调用超时给出）。
 *
 * <p>{@code destroyMethod="shutdown"}：容器关闭时优雅停池（不设 daemon 也行，
 * 但这里仍把线程设为 daemon —— 见 {@link #dashboardExecutor(int)} 注释）。
 */
@Configuration
public class DashboardExecutorConfig {

    /** bean 名（注入点用 {@code @Qualifier} 引用，避免与将来别的池撞名） */
    public static final String DASHBOARD_EXECUTOR = "dashboardExecutor";

    @Bean(name = DASHBOARD_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService dashboardExecutor(@Value("${mall.admin.dashboard.parallelism:8}") int parallelism) {
        // P8-4：外面再包一层 MdcPropagatingExecutor —— 让并行聚合的三条出站调用继承调用方的 traceId
        //   （否则工作线程里 MDC 为空 ⇒ 出站不带 X-Trace-Id ⇒ "网关→admin→trade"三段日志串不起来）。
        //   选择在 bean 这一层包而不是改调用方：池的语义（线程数/队列/daemon/关闭）一字不变，改动面最小。
        int size = Math.max(3, parallelism);   // 至少 3：一次 summary 就要同时占用 3 个线程，否则"并行"是假的
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "dashboard-agg-" + seq.incrementAndGet());
            // daemon：看板线程**不应该**阻止 JVM 退出（它只做"等下游答复"这一件事）
            thread.setDaemon(true);
            return thread;
        };
        return new MdcPropagatingExecutor(Executors.newFixedThreadPool(size, factory));
    }
}
