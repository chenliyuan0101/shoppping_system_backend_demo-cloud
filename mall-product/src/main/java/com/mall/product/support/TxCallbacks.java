package com.mall.product.support;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * "事务提交后执行"的小工具（P6-4/P6-5 反复踩到同一个坑，所以只留一份实现）。
 *
 * <h2>为什么必须有它（不是洁癖，是实测缺陷）</h2>
 * 在事务里做"让外部世界看见本次改动"的动作（投 MQ、写 ES、写缓存），如果**立刻**执行：
 * <ol>
 *   <li><b>读到旧状态</b>：消费方/下游可能在本地事务提交前被触发，读库读到的还是<b>旧值</b>，
 *       于是把旧值写进索引 —— 表现是"索引里的库存比真实库存多"，而且<b>不报任何错</b>。
 *       P6-4 维护窗口实测到的"幽灵索引文档"（`_id=22136`，库里已 `deleted=1`、索引里还在）
 *       正是这个形状：行内即时同步读到了提交前的状态，提交后没有重试。</li>
 *   <li><b>回滚后仍生效</b>：事务回滚了，消息/缓存已经发出去了 —— 下游会按"从没发生过的改动"去同步。</li>
 * </ol>
 * 两种错误的共同点是<b>静默</b>：日志正常、接口正常，只有数据不对。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>当前线程<b>有活跃事务同步</b>（{@code @Transactional} 内）⇒ 注册 {@code afterCommit} 回调；</li>
 *   <li>否则（无事务、或事务已结束）⇒ <b>立刻执行</b>；</li>
 *   <li>🆕 <b>已经处在"本事务的 afterCommit 阶段"时 ⇒ 立刻执行，不再注册</b>（见下）。</li>
 * </ul>
 * ⚠️ 只挂 {@code afterCommit}（不回滚也执行的那种）：回滚时**不**执行，这正是想要的语义。
 *
 * <h2>🆕 为什么必须处理"嵌套"（P6-5 施工中实测到的静默失效）</h2>
 * 两个层次都各自做了"提交后再执行"是**合理**的（后台写路径一处、发布方一处，见
 * {@code AdminProductServiceImpl} 与 {@code ProductSyncPublisher}），但两层叠加时会变成：
 * <pre>
 *   事务提交 → 外层回调开始执行（此时事务同步**仍然是 active**）→ 内层 afterCommitOrNow 又**注册**一个回调
 *   → 而 afterCommit 阶段已经开始，新注册的回调**再也不会被触发** ⇒ 消息永远不投、也不报错。
 * </pre>
 * 实测量到的现象（2026-09-14，可作为回归判据）：后台"建商品/逻辑删除"两个入口的索引同步**全部丢失**，
 * 只有"上下架"（那条路径当时没有事务）照常发布 ⇒ 建了又删的商品**永久留在索引里**（幽灵文档），
 * 是 `p6-index-content-audit.ps1` 报出来的（`INDEX-STALE`、幽灵 1 个）。
 * <p>修法：用一个线程内的"阶段"标记，把"提交已经发生"这件事显式表达出来 —— 处于该阶段时内层**立刻执行**
 * （语义完全正确：提交已经完成了），不再注册。回滚时该标记不设置，内层什么也不做（符合"回滚不执行"）。
 *
 * <h2>与缓存失效的关系</h2>
 * 本模块的缓存失效（{@code CacheService} 的写后失效）走的是同一套 {@code registerSynchronization}，
 * 口径一致 ⇒ 不存在"索引同步是提交前、缓存失效是提交后"这种半套的做法。
 */
public final class TxCallbacks {

    /**
     * 本线程当前所处的事务阶段。
     * <ul>
     *   <li>null ⇒ 未处在 afterCommit 回调执行期（可能不在事务里，也可能在事务体内）</li>
     *   <li>{@link #AFTER_COMMIT} ⇒ 本线程正在执行某个 {@code afterCommit} 回调</li>
     * </ul>
     * ⚠️ 生命周期只覆盖<b>一次回调执行</b>（{@code try/finally} 里清），不是整个事务 ——
     * 因为线程是复用的，任何"标记泄漏到下一个请求"都可能让副作用**提前执行**（读到未提交状态）。
     */
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
            // 已经处在 afterCommit 阶段：注册也不会被触发（见类注释）⇒ 立刻执行
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // ⚠️ 标记只在**本回调执行期间**有效（try/finally 立刻清掉），**不**依赖 afterCompletion：
                //    依赖 afterCompletion 清标记时，任何"绕过 Spring 收尾"的路径（测试里手写
                //    initSynchronization / clearSynchronization，或框架里直接 clear()）都会把标记留在
                //    **复用的线程**上 —— 实测过：下一个套件里"提交前不该执行"的断言拿到 `[deferred]`，
                //    被误判成实现坏了。作用域收在本回调内就能覆盖全部嵌套场景（嵌套永远发生在
                //    "正在执行的那一个回调"里）。
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
