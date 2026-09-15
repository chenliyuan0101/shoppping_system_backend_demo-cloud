package com.mall.product.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mall.common.support.TxCallbacks;

/**
 * {@link TxCallbacks} 的单元测试 —— 重点是**嵌套调用**那条（P6-5 施工中实测到的静默失效）。
 *
 * <h2>为什么值得单独钉住（这不是"测工具类"的形式主义）</h2>
 * 生产上两层都各自做了"提交后再执行"：后台写路径一层（{@code AdminProductServiceImpl}）、
 * 发布方一层（{@code ProductSyncPublisher}）。两层叠加时，内层会在<b>外层 afterCommit 正在执行</b>时
 * 去 {@code registerSynchronization} —— 而那个阶段已经开始，新注册的回调**永远不会被触发**：
 * <pre>
 *   实测（2026-09-14）：后台"建商品"与"逻辑删除"的索引同步**全部丢失**、无任何异常；
 *   只有"上下架"（那条路径当时没有事务）照常发布 ⇒ 建了又删的商品永久留在索引里，
 *   由 p6-index-content-audit.ps1 报出 INDEX-STALE（幽灵 1 个）。
 * </pre>
 * 本类把这条钉死：**内层必须在外层回调里立刻执行**，而不是再注册一个永远不会跑的回调。
 *
 * <h2>为什么可以手写 {@code initSynchronization()}</h2>
 * 这里不需要真事务：{@code afterCommitOrNow} 判据只有"同步是否活跃 + 是否已进入提交阶段"两条，
 * 手写同步管理器正好把这两条单独摆出来（真库事务那一路由 {@code AdminIndexSyncAfterCommitMySqlTest} 覆盖）。
 * ⚠️ 用真事务的用例**不能**验证这条：测试自带事务时 {@code afterCommit} 根本不会触发（本模块踩过）。
 */
class TxCallbacksTest {

    /**
     * ⚠️ 每个用例结束都要**把事务生命周期走完**（afterCompletion 一定会被调用，生产上由 Spring 保证）：
     * {@link TxCallbacks} 的阶段标记就是靠 {@code afterCompletion} 清的 —— 测试里漏掉它，
     * 标记会留在**复用的线程**上，导致下一个用例被误判成"已经提交过"而立刻执行。
     * （第一版就是这么红的：`提交前一条都不许执行` 收到 `[deferred]`。）
     */
    @AfterEach
    void finishTransactionLifecycle() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            List<TransactionSynchronization> syncs =
                    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("[事务外] 没有活跃同步 ⇒ 立刻执行（不注册）")
    void withoutTransaction_runsImmediately() {
        List<String> ran = new ArrayList<>();
        TxCallbacks.afterCommitOrNow(() -> ran.add("now"));
        assertEquals(List.of("now"), ran);
    }

    @Test
    @DisplayName("[事务内] 提交前不执行；afterCommit 才执行；回滚（只有 afterCompletion）不执行")
    void insideTransaction_defersUntilCommit() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        TxCallbacks.afterCommitOrNow(() -> ran.add("deferred"));

        assertEquals(List.of(), ran, "提交前一条都不许执行");
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, syncs.size());

        syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertEquals(List.of(), ran, "回滚 ⇒ 不执行（只挂 afterCommit，不挂 afterCompletion）");

        syncs.forEach(TransactionSynchronization::afterCommit);
        assertEquals(List.of("deferred"), ran, "提交后执行一次");
    }

    @Test
    @DisplayName("[嵌套] 外层回调里再调用 ⇒ 内层**立刻执行**（若又去注册，就永远不会被触发 —— 实测过的静默失效）")
    void nestedCall_insideAfterCommit_runsImmediately() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();

        // 模拟生产形状：外层（后台写路径）注册 → 回调里内层（发布方）又调一次
        TxCallbacks.afterCommitOrNow(() -> {
            ran.add("outer");
            TxCallbacks.afterCommitOrNow(() -> ran.add("inner"));
        });

        assertEquals(List.of(), ran, "提交前两条都不许执行");
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);

        assertEquals(List.of("outer", "inner"), ran,
                "内层必须在外层回调里立刻执行；若这里只看到 [outer]，说明它又注册了一个"
                        + "**永远不会被触发**的回调（索引同步会静默丢失，实测过）");
        assertEquals(1, syncs.size(), "内层不得新增同步（新增的那条不会被触发 ⇒ 等于丢失）");
    }

    @Test
    @DisplayName("[嵌套/回滚] 回滚路径下两个回调都不执行")
    void nestedCall_onRollback_runsNothing() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        TxCallbacks.afterCommitOrNow(() -> {
            ran.add("outer");
            TxCallbacks.afterCommitOrNow(() -> ran.add("inner"));
        });

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertTrue(ran.isEmpty(), "回滚 ⇒ 外层不执行、内层也不执行（内层根本不该被触发）");
    }

    @Test
    @DisplayName("[线程复用] afterCompletion 清掉阶段标记 ⇒ 同一线程的下一个请求仍按'无事务'立刻执行")
    void phaseFlag_isClearedAfterCompletion() {
        List<String> ran = new ArrayList<>();
        TransactionSynchronizationManager.initSynchronization();
        TxCallbacks.afterCommitOrNow(() -> ran.add("first"));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        TransactionSynchronizationManager.clearSynchronization();

        // 同一个线程（本用例就是同一线程）再调一次：此时没有事务 ⇒ 必须立刻执行
        TxCallbacks.afterCommitOrNow(() -> ran.add("second"));
        assertEquals(List.of("first", "second"), ran,
                "阶段标记必须在 afterCompletion 清掉，否则下一个请求会被误判成'已提交'");
    }
}
