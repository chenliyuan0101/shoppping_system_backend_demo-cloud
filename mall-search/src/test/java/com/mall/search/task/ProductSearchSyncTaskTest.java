package com.mall.search.task;

import com.mall.search.service.ProductSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>兜底 drain 任务的批量语义套件（单元级，不连 Redis/ES）</b>。
 *
 * <h2>为什么单独一批用例</h2>
 * P6-5 把这条任务从"逐条 {@code syncProduct}"改成"整批一次 {@code syncProducts}"。
 * 这条改动最容易被写坏的两个点，恰好都是**看不出来**的：
 * <ol>
 *   <li>改回逐条（写放大回来：45 个 id ≈60s），而 logs 里只是"处理 45 个"；</li>
 *   <li>失败列表丢失或不再重新入队 ⇒ 那些商品的索引**静默停更**（Redis 集合已经 SPOP 走了，
 *       不再 markDirty 回去就永远没人再同步它们）。</li>
 * </ol>
 * 所以这里用 mock 把"调用了什么"钉死：**整批一次** + **失败原样回队**。
 *
 * <p>⚠️ 边界说明：本类是**单元级**，"整批一次调用"不等于"真的只 refresh 一次"——
 * 后者由 {@code ProductIndexBatchWriteEsTest}（真 ES + spy 计数）证明。两者合起来才是完整判据。
 */
class ProductSearchSyncTaskTest {

    private ProductSearchService productSearchService;
    private ProductSearchSyncTask task;

    @BeforeEach
    void setUp() {
        productSearchService = mock(ProductSearchService.class);
        task = new ProductSearchSyncTask(productSearchService);
    }

    @Test
    @DisplayName("[drain/批量] 一轮取出的 N 个 id 只调**一次** syncProducts（不逐条），全部成功 → 不重新入队")
    void flushPending_syncsWholeBatchInOneCall() {
        List<Long> pending = List.of(1L, 2L, 3L);
        when(productSearchService.drainPending(200)).thenReturn(pending);
        when(productSearchService.syncProducts(pending)).thenReturn(List.of());

        task.flushPending();

        verify(productSearchService, times(1)).drainPending(200);
        verify(productSearchService, times(1)).syncProducts(pending);
        // 逐条入口**不许**再被这条任务碰（碰了就是 N 次写 + N 次 refresh = 写放大回来）
        verify(productSearchService, never()).syncProduct(anyLong());
        verify(productSearchService, never()).markDirty(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("[drain/45 个 id] 一次调用（验收里那个批次规模），不是 45 次")
    void flushPending_45ids_isOneCall() {
        List<Long> pending = new ArrayList<>();
        for (long i = 1; i <= 45; i++) {
            pending.add(9_200_000_000L + i);
        }
        when(productSearchService.drainPending(200)).thenReturn(pending);
        when(productSearchService.syncProducts(pending)).thenReturn(List.of());

        task.flushPending();

        verify(productSearchService, times(1)).syncProducts(pending);
        verify(productSearchService, never()).syncProduct(anyLong());
    }

    @Test
    @DisplayName("[drain/失败回队] 失败的 id **原样**重新入队（否则它们的索引永久停更，且没有任何日志）")
    void flushPending_requeuesFailedIds() {
        List<Long> pending = List.of(1L, 2L, 3L);
        List<Long> failed = List.of(2L, 3L);
        when(productSearchService.drainPending(200)).thenReturn(pending);
        when(productSearchService.syncProducts(pending)).thenReturn(failed);

        task.flushPending();

        verify(productSearchService, times(1)).markDirty(failed);
    }

    @Test
    @DisplayName("[drain/空队列] 队列为空 ⇒ 一次同步都不发起（不做无意义的 ES 往返）")
    void flushPending_emptyQueue_touchesNothing() {
        when(productSearchService.drainPending(200)).thenReturn(List.of());

        task.flushPending();

        verify(productSearchService, never()).syncProducts(org.mockito.ArgumentMatchers.any());
        verify(productSearchService, never()).markDirty(org.mockito.ArgumentMatchers.any());
    }
}
