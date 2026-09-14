package com.mall.admin.config;

import org.slf4j.MDC;
import org.springframework.lang.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 让线程池里的任务**继承提交方线程的 MDC**（P8-4 收尾）。
 *
 * <h2>为什么需要它</h2>
 * 看板的并行聚合（{@code AdminDashboardServiceImpl}）把"调 trade / product / user-center"三个出站调用
 * 提交到 {@link DashboardExecutorConfig} 的线程池。而 MDC 是 **ThreadLocal**：
 * 工作线程里 {@code traceId} 是空的 ⇒
 * <ol>
 *   <li>这三条出站调用**不会**带 {@code X-Trace-Id}（出站拦截器取不到值），下游各自生成新 id ⇒
 *       "网关 → mall-admin → mall-trade"三段日志**串不起来**（实测过：网关给的 id 只在 mall-admin 里出现）；</li>
 *   <li>工作线程里打的业务日志也没有 traceId。</li>
 * </ol>
 * 这个包装类在**提交任务时**抓一份 MDC 快照，在**工作线程执行前**装回去、**执行后**恢复原状。
 *
 * <h2>三个刻意的细节</h2>
 * <ul>
 *   <li><b>只包装 {@code execute}</b>：{@link AbstractExecutorService#submit} 内部会走 {@code newTaskFor} → {@code execute}
 *       ⇒ {@code submit}/{@code invokeAll} 等都被覆盖，不需要逐个重写。</li>
 *   <li><b>执行后恢复而不是清空</b>：线程池的线程是复用的，"上一个任务的 MDC"必须先存后还原，
 *       否则会把 id 泄漏给后续无关任务（那比没有 id 更难查）。</li>
 *   <li><b>只委托不改语义</b>：除 {@code execute} 外全部原样委托，线程数/队列/拒绝策略/关闭行为都不变。</li>
 * </ul>
 */
public class MdcPropagatingExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;

    public MdcPropagatingExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(@NonNull Runnable command) {
        Map<String, String> submitterContext = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            Map<String, String> workerContextBefore = MDC.getCopyOfContextMap();
            if (submitterContext == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(submitterContext);
            }
            try {
                command.run();
            } finally {
                if (workerContextBefore == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(workerContextBefore);
                }
            }
        });
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    /** 仅供测试/排障：暴露被包装的池（不参与业务逻辑） */
    List<Runnable> drainForTest() {
        return Collections.emptyList();
    }
}
