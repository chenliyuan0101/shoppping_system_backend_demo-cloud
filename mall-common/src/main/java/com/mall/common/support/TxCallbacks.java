package com.mall.common.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "事务提交后执行"的小工具（自持副本，与 {@code mall-product} 的同名类逐字同构）。
 *
 * <h2>为什么管理端也需要它（P7 §5 第 8 条：跨服务副作用一律走 afterCommit）</h2>
 * 禁用管理员时要做两件"让外部世界看见"的事：写 {@code mall:cache:admin:status:{id}=0}
 * 与 bump {@code mall:token:ver:admin:{id}}。如果它们留在本地事务里**立刻**执行：
 * <ol>
 *   <li><b>回滚后仍生效</b>：事务回滚了（比如后续步骤抛异常），缓存里已经写着"禁用"
 *       ⇒ 网关拿着这份缓存给一个**并没有被禁用**的管理员回 {@code 403 账号已被禁用}，
 *       而且它会一直错到 TTL（10 分钟）到期或被对账任务纠正。</li>
 *   <li>反之若是"先 bump 版本再写状态"，回滚后管理员会莫名其妙被踢下线。</li>
 * </ol>
 * 两种错误的共同点是<b>静默</b>：接口报错、日志正常，只有"别人看到的状态"不对。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>当前线程<b>有活跃事务同步</b>（{@code @Transactional} 内）⇒ 注册 {@code afterCommit} 回调；</li>
 *   <li>否则（无事务、或提交阶段已开始）⇒ <b>立刻执行</b>；</li>
 *   <li>只挂 {@code afterCommit}（挂 afterCompletion 会在回滚时也执行 —— 那不是我们想要的）。</li>
 * </ul>
 *
 * @see com.mall.admin.service.impl.AdminStatusServiceImpl 的用法（改状态 → 提交后写缓存 + bump）
 */
public final class TxCallbacks {

    /** 本线程当前是否正处在某个 afterCommit 回调的执行期 */
    private static final ThreadLocal<Object> COMMITTED = new ThreadLocal<>();

    private static final Object AFTER_COMMIT = new Object();

    private TxCallbacks() {
    }

    /** 在事务里 ⇒ 注册 afterCommit 回调；不在事务里、或提交阶段已开始 ⇒ 立刻执行 */
    public static void afterCommitOrNow(Runnable action) {
        if (action == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        if (COMMITTED.get() == AFTER_COMMIT) {
            // 已经处在 afterCommit 阶段：再注册也不会被触发（P6-5 实测过的静默失效）⇒ 立刻执行
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 标记只在**本回调执行期间**有效（try/finally 立刻清），不依赖 afterCompletion
                // —— 否则标记会泄漏到复用的线程上，让下一次请求的副作用**提前执行**。
                COMMITTED.set(AFTER_COMMIT);
                try {
                    action.run();
                } finally {
                    COMMITTED.remove();
                }
            }
        });
    }
}
