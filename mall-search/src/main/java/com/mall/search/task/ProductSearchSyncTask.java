package com.mall.search.task;

import com.mall.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 商品索引增量同步的**兜底消费任务**：消费 {@link ProductSearchService#markDirty} 写入 Redis 的待同步队列。
 *
 * <p>现在的分工（2026-09-11 起引入 RabbitMQ）：
 * <ul>
 *   <li><b>主通道</b>：{@code ProductSyncPublisher} 投递到 {@code mall.pms.es-sync}，消费者近实时落 ES
 *       （失败自动延迟重试，超限进死信队列 {@code mall.pms.es-sync.dlq}）</li>
 *   <li><b>兜底通道（本任务）</b>：只在"MQ 关闭或投递失败"时才会有数据进 Redis 集合；
 *       定时批量消费，失败的 id 重新入队等下一轮</li>
 * </ul>
 *
 * <p>为什么保留：MQ 不可用/投递失败时索引不能就此停更；而"最终一致"的旁路链路
 * 有两条独立通道 + 全量重建兜底，才敢说"不会长期不一致"。
 *
 * <p>两条通道都调用同一套落索引实现（{@code ProductSearchService#syncProducts}：一次取内容 +
 * 一次 bulk + 恰好一次 refresh），语义完全一致。
 *
 * <p>⚠️ <b>P6-5 批量路径收口</b>：本任务原来对每个 id 调一次 {@code syncProduct}（= N 次写 + N 次 refresh，
 * 45 个 id ≈60s）。现在整批一次 {@code syncProducts}，花费与"一批一次 bulk"相当（45 个 id ≈0.3~1s）；
 * 失败重新入队的语义**一字未改**（失败列表由 ES bulk 的 per-item 结果映射回来）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSearchSyncTask {

    /** 单轮最多处理多少个商品(避免一次拉太多把 ES 打满) */
    private static final int MAX_PER_RUN = 200;

    private final ProductSearchService productSearchService;

    @Scheduled(fixedDelayString = "${mall.search.sync-interval-ms:15000}", initialDelay = 20_000)
    public void flushPending() {
        List<Long> pending = productSearchService.drainPending(MAX_PER_RUN);
        if (pending.isEmpty()) {
            return;
        }
        // 🆕 P6-5 批量路径收口：整批一次 syncProducts（一次取内容 + 一次 bulk + **恰好一次 refresh**），
        //    不再逐条 syncProduct（那会让这一轮 = N 次写 + N 次 refresh，45 个 id ≈60s）。
        //    失败重新入队的语义不变：失败列表由 ES bulk 的 per-item 结果映射回来，原样 markDirty 回去。
        List<Long> failed = productSearchService.syncProducts(pending);
        if (!failed.isEmpty()) {
            productSearchService.markDirty(failed);   // 失败重新入队，等下一轮
        }
        log.debug("商品索引增量同步：处理 {} 个(失败 {} 个已重新入队)", pending.size(), failed.size());
    }
}
