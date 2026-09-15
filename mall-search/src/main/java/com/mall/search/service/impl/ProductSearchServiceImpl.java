package com.mall.search.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.mall.search.client.ProductIndexDocClient;
import com.mall.search.dto.EsClusterInfo;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductIdPage;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.mq.ProductSyncPublisher;
import com.mall.search.service.ProductSearchService;
import com.mall.search.support.BusinessException;
import com.mall.search.support.CacheKeys;
import com.mall.common.support.CacheService;
import com.mall.search.support.constant.EnableStatus;
import com.mall.search.support.dto.ReindexResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 商品检索实现(Elasticsearch)。
 *
 * <p>全量重建流程：取数(向 product 逐页拉) → 删旧索引 → 建索引(显式映射) → bulk 写入 → refresh。
 * 文档数 = 在架 SPU 数；下架/删除的商品不会进索引。
 *
 * <h2>⚠️ 与单体那版 {@code pms.ProductSearchServiceImpl} 的**唯一**差别：文档从哪来</h2>
 * 单体是直接读 {@code pms_spu}/{@code pms_sku}/{@code pms_brand} 拼文档的；拆开之后
 * **本服务没有 MySQL**，所以内容改为**向 mall-product 拉**
 * （{@code POST /internal/v1/product/index-docs}，见 {@link ProductIndexDocClient}）。
 * 具体只动了三个"取数"点，其余 ES 逻辑（查询/排序/映射/分词器探测/重建流程/失败口径）**逐字保留**：
 * <pre>
 * 现状(单体)                                 本服务(P6-2)
 * reindex:  spuMapper.selectList(status=1)     → productIndexDocClient.page(逐页)
 *           skuMapper.selectShelfAggregates()  ┐
 *           brandMapper.selectList(null)       ┘（这些聚合口径**搬到了 product 侧**，逐字不变）
 * syncProduct(spuId): spuMapper.selectById     → productIndexDocClient.bySpuIds(List.of(spuId))
 * syncByBrand(brandId): spuMapper 按 brand 查   → productIndexDocClient.byBrand(brandId)
 * </pre>
 * 其余方法（{@code markDirty}/{@code syncLater}/{@code syncBrandLater}/{@code drainPending}/
 * {@code pendingCount}/{@code count}/{@code titleAnalyzer}/{@code findById}/{@code searchByTitle}/
 * {@code search}/{@code deleteProduct}）**一行未改**——它们本来就只依赖 ES 与 Redis。
 *
 * <h2>⚠️ 重建失败不得"写出半截索引却声称成功"（规格 §6）</h2>
 * 所以取数**全部拉完再动索引**（与单体的顺序一致：先取数、后 recreateIndex）：
 * product 不可达时在第一步就抛错，索引保持原样（不会出现"删了旧的、新的没写进去"）。
 *
 * <h2>⚠️ 为什么"下架/删除"要靠**差集**判断</h2>
 * product 的 {@code index-docs} 只返回**在架且未删除**的商品，所以
 * "请求的 spuId − 返回的 spuId" 就是应该**从索引删除**的那些
 * （这正是单体 {@code syncProduct} 里 {@code spu == null || status != ENABLED → deleteProduct} 的等价物）。
 *
 * <h2>⚠️ P6-5 #3/#4：批量路径改成"bulk + 恰好一次 refresh"（写放大收口）</h2>
 * 现状（P6-2 起）的 {@code syncByBrand} 是**逐篇** {@code index(..., refresh(WaitFor))}：
 * 每一篇都要等一次 ES 刷新，实测 ≈1.27s/篇 ⇒ 品牌 937（45 篇）**60.9s**（P6-5 改前基线实测值）。
 * 现在改成：内容一次取齐 → **一次（或按 {@link #BULK_BATCH_SIZE} 分块的一次）bulk 写入** →
 * **整批结束后恰好一次 refresh**。语义不变（"调用返回时本批已可检索"），代价从"每篇一次刷新等待"
 * 变成"一次刷新的耗时"。
 *
 * <p>⚠️ **单篇路径刻意保持原样**（{@code sync/{spuId}}、{@code delete/{spuId}} 仍旧
 * {@code refresh(WaitFor)}）：它们本来就只写一篇，"一次刷新"就是它们的全部成本，
 * 而 bulk 对单篇没有收益；更重要的是 {@code POST /sync/{spuId}} 的对外承诺是
 * "**返回即已按最新状态落索引并可检索**"（后台改完商品要"改完就能搜到"），
 * 去掉那次刷新会让承诺变成"最多 1s 后可见"。批量路径没有这个承诺的损失（整批一次性可见），
 * 所以两处的取舍不同，这是**有意的**，不是漏改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    /** 重建时每页向 product 拉多少条（P6-1 侧的产品量级下 500 是一次往返的合理批量） */
    private static final int REINDEX_PAGE_SIZE = 500;

    /** 分页防呆：最多翻多少页（防止 product 侧 total 异常导致无限循环） */
    private static final int REINDEX_MAX_PAGES = 1000;

    /** bulk 写入的批大小（与单体一致） */
    private static final int BULK_BATCH_SIZE = 500;

    private final ElasticsearchClient elasticsearchClient;
    /** 索引文档内容的**唯一**来源（本服务没有库；规格 §3） */
    private final ProductIndexDocClient productIndexDocClient;
    /** 订单/售后链路的索引增量同步标记队列(Redis Set，MQ 不可用时的兜底通道) */
    private final CacheService cacheService;
    /** 索引同步消息投递端(MQ 主通道) */
    private final ProductSyncPublisher productSyncPublisher;

    /**
     * 标题分词器配置：{@code auto}(默认)= 按 smartcn → cjk → standard 顺序探测可用者。
     * 也可显式指定(如 {@code smartcn})；指定但不可用时仍回落到探测链。
     */
    @Value("${mall.search.title-analyzer:auto}")
    private String configuredTitleAnalyzer;

    /** 探测结果缓存(进程内不变；装插件重启后首次重建会重新探测) */
    private volatile String resolvedTitleAnalyzer;

    /**
     * 索引是否已确认存在（映射正确）。
     *
     * <p>避免每条同步消息都多做一次 {@code indices.exists} 往返；由 {@code reindex()} 与
     * {@code ensureIndex()} 置位。
     */
    private volatile boolean indexEnsured = false;

    @Override
    public ReindexResult reindex() {
        long start = System.currentTimeMillis();

        // ===== 1) 取数：向 product **逐页**拉在架商品的索引文档 =====
        //  ⚠️ 先在内存里取齐再动索引：product 不可达时必须"索引保持原样"，
        //     而不是"删了旧索引、新文档没写进去"（规格 §6：不得写出一份半截索引却声称成功）。
        List<ProductSearchDoc> docs = new ArrayList<>();
        long onShelfTotal;
        try {
            onShelfTotal = fetchAllDocs(docs);
        } catch (Exception e) {
            log.error("商品索引重建失败(取数阶段，索引未被改动): product 侧取索引文档失败", e);
            throw new BusinessException(500, "商品索引重建失败：" + e.getMessage());
        }

        // ===== 2) 重建索引(全量重建语义：删旧 → 建新，避免下架商品残留在索引里) =====
        long indexed = 0L;
        try {
            recreateIndex();

            // ===== 3) bulk 写入(文档 _id = spuId → 重复写入天然幂等) =====
            //  ⚠️ P6-5 #3 的核对结论：本方法**本来就没有**逐篇 refresh（写入走 bulk、
            //     刷新只在整个循环之后做一次，见第 4 步）——写放大不在 reindex，而在 syncByBrand。
            //     这里把"构造 bulk 操作"抽成 {@link #bulkOps}，让两条批量路径**共用同一份**写法，
            //     避免以后有人在其中一条里又加回逐篇刷新。
            for (int from = 0; from < docs.size(); from += BULK_BATCH_SIZE) {
                List<ProductSearchDoc> batch = docs.subList(from, Math.min(from + BULK_BATCH_SIZE, docs.size()));
                BulkResponse response = elasticsearchClient.bulk(b -> b.operations(indexOps(batch)));
                if (response.errors()) {
                    response.items().stream().filter(item -> item.error() != null).limit(3)
                            .forEach(item -> log.warn("索引写入失败 spuId={} reason={}", item.id(), item.error().reason()));
                }
                indexed += response.items().stream().filter(item -> item.error() == null).count();
            }

            // ===== 4) 刷新，保证重建后立即可检索（**整批恰好一次**，不许挪进循环里） =====
            elasticsearchClient.indices().refresh(r -> r.index(INDEX));
        } catch (Exception e) {
            log.error("商品索引重建失败", e);
            throw new BusinessException(500, "商品索引重建失败：" + e.getMessage());
        }

        long took = System.currentTimeMillis() - start;
        log.info("商品索引重建完成 index={} 分词器={} 在架SPU={} 写入={} 耗时={}ms",
                INDEX, resolvedTitleAnalyzer, onShelfTotal, indexed, took);
        return new ReindexResult(INDEX, resolvedTitleAnalyzer, indexed, onShelfTotal, took);
    }

    @Override
    public ProductIdPage search(String keyword, List<Long> categoryIds, Long brandId,
                                Long minPrice, Long maxPrice, String sort,
                                long pageNum, long pageSize) {
        try {
            SearchResponse<ProductSearchDoc> response = elasticsearchClient.search(s -> s
                            .index(INDEX)
                            .from((int) ((pageNum - 1) * pageSize))
                            .size((int) pageSize)
                            .trackTotalHits(t -> t.enabled(true))
                            .query(buildQuery(keyword, categoryIds, brandId, minPrice, maxPrice))
                            .sort(buildSort(sort)),
                    ProductSearchDoc.class);

            List<Long> spuIds = response.hits().hits().stream()
                    .map(hit -> hit.source() == null ? null : hit.source().getSpuId())
                    .filter(Objects::nonNull)
                    .toList();
            long total = response.hits().total() == null ? spuIds.size() : response.hits().total().value();
            return new ProductIdPage(spuIds, total);
        } catch (Exception e) {
            // 交给调用方决定降级(前台货架会回落 MySQL LIKE，那在 product 侧)；这里只补充上下文后抛出
            throw new IllegalStateException("Elasticsearch 检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean syncProduct(long spuId) {
        try {
            // 内容改为向 product 拉（单体这里是 spuMapper.selectById(spuId)）
            IndexDocsResult result = productIndexDocClient.bySpuIds(List.of(spuId));
            ProductSearchDoc doc = result.docs().stream()
                    .filter(d -> d.getSpuId() != null && d.getSpuId() == spuId)
                    .findFirst().orElse(null);
            if (doc == null) {
                deleteProduct(spuId);   // 下架/删除/不存在 → 从索引移除
                return true;
            }
            return indexDoc(doc);
        } catch (Exception e) {
            // 不同步失败阻塞商品写操作：与 Redis fail-open 一致，仅告警，等重试/全量重建筑底
            log.warn("商品索引同步失败 spuId={}（可稍后全量重建）: {}", spuId, e.getMessage());
            return false;
        }
    }

    @Override
    public List<Long> syncProducts(Collection<Long> spuIds) {
        // 入参**保序**（保留重复与 null）：失败列表必须能"逐条映射回原输入"，
        // 否则消费侧的 failed → 重试队列 → DLQ 那条链上的条数与内容会与原实现漂。
        if (spuIds == null || spuIds.isEmpty()) {
            return List.of();   // 原逐条实现：空集合 ⇒ 循环不执行 ⇒ failed 为空（一次 ES 都不碰）
        }
        List<Long> input = spuIds.stream().toList();   // 允许 null 元素（List.copyOf 不允许）
        List<Long> ids = input.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            // 全是 null ⇒ 每个 null 都是一条"失败"（与原实现的 `spuId == null → failed.add(null)` 一致）
            return input;
        }

        long start = System.currentTimeMillis();
        int docsToWrite = 0;
        int docsToDelete = 0;
        Set<Long> failedIds;
        try {
            // ① 一次向 product 取齐内容（不再逐条回拉 —— N+1 的坑 P6-2 已经踩过一次）
            IndexDocsResult result = productIndexDocClient.bySpuIds(ids);
            Map<Long, ProductSearchDoc> byId = new LinkedHashMap<>();
            for (ProductSearchDoc doc : result.docs()) {
                if (doc.getSpuId() != null) {
                    byId.putIfAbsent(doc.getSpuId(), doc);
                }
            }
            List<ProductSearchDoc> docs = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
            // ② 请求了但内容源没给 ⇒ 下架/删除/不存在 ⇒ 从索引删除（与 syncProduct 的差集口径一致）
            List<Long> missing = ids.stream().filter(id -> !byId.containsKey(id)).toList();
            docsToWrite = docs.size();
            docsToDelete = missing.size();
            // ③ 一次 bulk + 整批恰好一次 refresh；失败按 **ES bulk 的 per-item 结果**映射回 id
            failedIds = bulkWriteBatch(ids, docs, missing);
        } catch (Exception e) {
            // 取内容阶段就失败：与逐条实现一致 —— **这批全部按失败处理**，且索引一篇都不动
            log.warn("商品索引批量同步取内容失败（{} 个 id 全部按失败处理，索引未改动）: {}",
                    ids.size(), e.getMessage());
            failedIds = new HashSet<>(ids);
            docsToWrite = 0;
            docsToDelete = 0;
        }

        Set<Long> failedLookup = failedIds;
        List<Long> failed = new ArrayList<>();
        for (Long id : input) {
            if (id == null || failedLookup.contains(id)) {
                failed.add(id);
            }
        }
        log.info("商品索引批量同步完成 批次={} 待写入={} 待删除={} 失败={} 耗时={}ms",
                ids.size(), docsToWrite, docsToDelete, failedLookup.size(), System.currentTimeMillis() - start);
        return failed;
    }

    @Override
    public void deleteProduct(long spuId) {
        try {
            elasticsearchClient.delete(d -> d.index(INDEX).id(String.valueOf(spuId)).refresh(Refresh.WaitFor));
        } catch (Exception e) {
            log.warn("商品索引删除失败 spuId={}: {}", spuId, e.getMessage());
        }
    }

    @Override
    public int syncByBrand(long brandId) {
        // 内容改为向 product 拉（单体这里是"按 brandId 查在架 spu 再逐个 syncProduct"）。
        // ⚠️ 2026-09-14 实测踩到并修掉的一个**我自己引入的**缺陷：
        //    第一版这里对每个 doc 调 syncProduct(doc.getSpuId())，而 syncProduct 会**再向 product 拉一次内容**
        //    ⇒ 品牌 937（45 个在架商品）变成 **45 次 HTTP 往返**，live 实测 **59,993ms**（`-m 20` 的调用直接被超时切断，
        //    表现成"返回空 data"）。大品牌（几百个商品）会更糟，而且这条路径是 **MQ 消费者**在用的。
        //    修法：内容已经**一次取齐**（byBrand 的返回值就是完整文档），直接写索引，不再逐条回拉。
        //
        // 🆕 P6-5 #3/#4（写放大收口）：写入形态从"逐篇 index(..., Refresh.WaitFor)"改成
        //    "整批 bulk + **恰好一次** refresh"。改前基线（活体实测，品牌 937 = 45 篇）：
        //      · 逐篇 refresh(WaitFor)：**60,906 ms**（≈1.35s/篇；P6-5 规格 §一 表格第 3 行记的
        //        "45 篇 ≈57s"与 P6-2 汇报里品牌 937 的 59,993ms 就是同一类数字）
        //      · 该数字也是 P6-5 #4 的前置：product 的 syncBrandLater 之所以"不敢转发"就是因为这个耗时。
        //    语义**不变**：整批写完后 refresh 一次 ⇒ 本调用返回时这一批全都可检索（同 `Refresh.WaitFor` 的承诺）。
        //    返回值语义也不变：仍是"成功写入 ES 的条数"（失败的那些不计入，ES 不可达时是 0）。
        //    ⚠️ 2026-09-14（P6-5 批量路径收口）：写入体与 {@link #syncProducts} **共用** {@link #bulkWriteBatch}，
        //       于是"bulk + 恰好一次 refresh + per-item 失败映射"只有一份实现，不会再出现两条路径各自漂移。
        IndexDocsResult result = productIndexDocClient.byBrand(brandId);
        List<ProductSearchDoc> docs = result.docs().stream()
                .filter(doc -> doc.getSpuId() != null)
                .toList();
        if (docs.isEmpty()) {
            log.info("品牌 {} 索引同步完成 0/{}（该品牌在架商品为空，索引未改动）", brandId, result.size());
            return 0;
        }
        long start = System.currentTimeMillis();
        List<Long> ids = docs.stream().map(ProductSearchDoc::getSpuId).toList();
        int synced = docs.size() - bulkWriteBatch(ids, docs, List.of()).size();
        log.info("品牌 {} 索引同步完成 {}/{}（bulk+单次 refresh，耗时 {}ms）",
                brandId, synced, result.size(), System.currentTimeMillis() - start);
        return synced;
    }

    /**
     * <b>批量写 / 删索引的唯一落点</b>（{@code syncProducts} 与 {@code syncByBrand} 共用）：
     * 一次 bulk（超过 {@link #BULK_BATCH_SIZE} 才分块）+ **整批恰好一次 refresh**；
     * 返回**失败**的 spuId 集合（成功的、以及"删一个本来就不存在的文档"都不在里面）。
     *
     * <p>⚠️ 这是 P6-5 #3 的核心落点，四条纪律写在代码里：
     * <ol>
     *   <li><b>不许在循环里 refresh</b>：逐篇 {@code refresh(WaitFor)} 就是写放大的成因
     *       （每篇要等一次 ES 刷新；45 篇实测 60,906 ms）；</li>
     *   <li><b>返回前必须 refresh 一次</b>：否则"调用返回 = 已可检索"这条承诺就没了
     *       （表现是"同步成功了但搜不到"，而调用方拿到的还是 true）；</li>
     *   <li><b>只对 {@link #INDEX} 这一次 refresh</b>，不去刷整个集群；</li>
     *   <li><b>失败按 ES bulk 的 per-item 结果映射回 id</b>（不是"整批一起成败"）：
     *       只有 {@code item.error() != null} 的才算失败；{@code result=not_found}（删一个不存在的
     *       文档）**不算失败** —— 与单篇 {@code deleteProduct} 的"幂等吞掉"口径一致。</li>
     * </ol>
     *
     * <p>异常口径与逐条实现**逐条对齐**：bulk 或 refresh 抛异常（ES 不可达等）⇒
     * **这批全部算失败** —— 写入可能已经生效，但"不保证可见/不保证写全"，
     * 按失败走重试是**幂等**的；反过来（报成功）则可能永久丢一篇，那才是不可接受的。
     *
     * @param allIds        本批涉及的全部 spuId（**非空、已去重**；异常时整批按失败返回）
     * @param docs          内容源给出、要写入的文档
     * @param idsToDelete   内容源没给（下架/删除/不存在）、要从索引删除的 spuId
     */
    private Set<Long> bulkWriteBatch(List<Long> allIds, List<ProductSearchDoc> docs, List<Long> idsToDelete) {
        Set<Long> failed = new HashSet<>();
        try {
            List<BulkOperation> ops = new ArrayList<>(docs.size() + idsToDelete.size());
            ops.addAll(indexOps(docs));
            for (Long spuId : idsToDelete) {
                ops.add(BulkOperation.of(op -> op.delete(d -> d.index(INDEX).id(String.valueOf(spuId)))));
            }
            if (ops.isEmpty()) {
                return failed;
            }
            for (int from = 0; from < ops.size(); from += BULK_BATCH_SIZE) {
                List<BulkOperation> chunk = ops.subList(from, Math.min(from + BULK_BATCH_SIZE, ops.size()));
                BulkResponse response = elasticsearchClient.bulk(b -> b.operations(chunk));
                for (BulkResponseItem item : response.items()) {
                    if (item.error() == null) {
                        continue;
                    }
                    failed.add(parseSpuId(item.id()));
                    // 与单篇路径同一句话前缀（便于 grep 同一条线索），后面接 bulk 的 per-item 详情
                    log.warn("商品索引同步失败 spuId={}（可稍后全量重建）: status={} reason={}",
                            item.id(), item.status(), item.error().reason());
                }
            }
            // 整批唯一的一次刷新：本方法返回后这一批即可检索（等价于逐篇 WaitFor 的可见性承诺）
            elasticsearchClient.indices().refresh(r -> r.index(INDEX));
        } catch (Exception e) {
            log.warn("商品索引批量写入失败（{} 个 id 全部按失败处理，可稍后重试/全量重建）: {}",
                    allIds.size(), e.getMessage());
            failed.addAll(allIds);
        }
        return failed;
    }

    /** bulk item 的 {@code _id} 就是 spuId（见上面构造操作的地方）；解析不了时返回 null 并告警 */
    private Long parseSpuId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            log.warn("bulk 返回了一个无法解析为 spuId 的 _id={}（该失败无法映射回具名 id）", id);
            return null;
        }
    }

    /** 把文档列表变成 bulk 的 index 操作（文档 _id = spuId ⇒ 覆盖写，天然幂等） */
    private List<BulkOperation> indexOps(List<ProductSearchDoc> docs) {
        return docs.stream()
                .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                        .index(INDEX)
                        .id(String.valueOf(doc.getSpuId()))
                        .document(doc))))
                .toList();
    }

    /** 把一份**已经取到内容**的索引文档写进 ES（失败只告警，返回 false） */
    private boolean indexDoc(ProductSearchDoc doc) {
        long spuId = doc.getSpuId();
        try {
            elasticsearchClient.index(i -> i
                    .index(INDEX)
                    .id(String.valueOf(spuId))
                    .document(doc)
                    .refresh(Refresh.WaitFor));   // 同步返回即可检索(单条写入，代价可接受)
            return true;
        } catch (Exception e) {
            log.warn("商品索引同步失败 spuId={}（可稍后全量重建）: {}", spuId, e.getMessage());
            return false;
        }
    }

    // ==================== private ====================

    @Override
    public void markDirty(Collection<Long> spuIds) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        List<Long> ids = spuIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        // ① 首选 MQ：**事务提交后**投递（消费失败会自动重试，超限进死信队列）
        // ② 兜底 Redis 集合：MQ 关闭/投递失败时由 ProductSearchSyncTask 定时批量消费
        productSyncPublisher.publishSpuIds(ids, () -> {
            String[] members = ids.stream().map(String::valueOf).toArray(String[]::new);
            long added = cacheService.setAdd(CacheKeys.esPendingSync(), members);
            if (added < 0) {
                log.warn("索引增量同步队列不可用(MQ 与 Redis 都不可用)，{} 个商品待全量重建筑底", members.length);
            }
        });
    }

    @Override
    public void syncLater(long spuId) {
        // 管理端写商品：MQ 可用则异步(请求不再受 ES 2s/5s 超时影响)，否则保持同步语义
        productSyncPublisher.publishSpuIds(List.of(spuId), () -> syncProduct(spuId));
    }

    @Override
    public void syncBrandLater(long brandId) {
        productSyncPublisher.publishBrand(brandId, () -> syncByBrand(brandId));
    }

    @Override
    public List<Long> drainPending(int max) {
        Set<String> members = cacheService.setPop(CacheKeys.esPendingSync(), max);
        return members.stream().map(Long::valueOf).toList();
    }

    @Override
    public long pendingCount() {
        return cacheService.setSize(CacheKeys.esPendingSync());
    }

    @Override
    public long count() {
        try {
            if (!indexExists()) {
                return -1L;
            }
            return elasticsearchClient.count(c -> c.index(INDEX)).count();
        } catch (Exception e) {
            log.warn("读取索引文档数失败: {}", e.getMessage());
            return -1L;
        }
    }

    @Override
    public String titleAnalyzer() {
        String cached = resolvedTitleAnalyzer;
        return cached != null ? cached : resolveTitleAnalyzer();
    }

    @Override
    public long markPending(Collection<Long> spuIds) {
        // 空入参**不碰 Redis**（直接 0）：调用方批量里出现空集合是常态，不该产生一次无意义往返，
        // 更不该因为 Redis 不可用就把"没有要标记的"报成 -1（那会让调用方以为标记失败而去重试）。
        if (spuIds == null || spuIds.isEmpty()) {
            return 0L;
        }
        String[] members = spuIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .map(String::valueOf)
                .toArray(String[]::new);
        if (members.length == 0) {
            return 0L;
        }
        // 只写 Redis 兜底集合（键与 search 自己的定时任务**逐字同一个**：CacheKeys.esPendingSync()）：
        //   · 写进去 ⇒ ProductSearchSyncTask.flushPending() 下一轮（默认 15s）会 drain 并 syncProduct；
        //   · 返回 SADD 的返回值（真正新增的条数）；Redis 不可用时是 -1（与 pendingCount 的 -1 同口径）。
        // ⚠️ MQ 主通道不在本方法里：按 P6-5 D1，发布方归 product（product 的 markDirty 已恢复
        //    "MQ 主 + Redis 兜底"）；这里再投一次会让同一个 spuId 被消费两遍。
        long added = cacheService.setAdd(CacheKeys.esPendingSync(), members);
        if (added < 0) {
            log.warn("标记待同步失败（Redis 不可用），{} 个商品只能靠 MQ/全量重建兜底: {}",
                    members.length, java.util.Arrays.toString(members));
        } else {
            log.debug("标记待同步 {} 个商品（新增 {}，已在集合内的不重复计数）", members.length, added);
        }
        return added;
    }

    @Override
    public EsClusterInfo esClusterInfo() {
        // 取法与单体 AdminEsController#ping() **逐字同一个**（clusterName/nodeName/esVersion/status/numberOfNodes）：
        // 单体将来把 /api/admin/es/ping 转发到本服务的 /status，值必须与它今天自取的一致（C1）。
        try {
            InfoResponse info = elasticsearchClient.info();
            HealthResponse health = elasticsearchClient.cluster().health();
            return new EsClusterInfo(info.clusterName(), info.name(), info.version().number(),
                    health.status().jsonValue(), health.numberOfNodes());
        } catch (Exception e) {
            // 自检接口在 ES 挂掉时必须**还能回答**（"不可用"是结论，不是异常）：
            // 四个字符串 null + numberOfNodes=-1（0 会被读成"集群里没有节点"）。
            log.warn("读取 ES 集群信息失败（按不可用上报）: {}", e.getMessage());
            return EsClusterInfo.unavailable();
        }
    }

    @Override
    public ProductSearchDoc getIndexedDoc(long spuId) {
        try {
            GetResponse<ProductSearchDoc> response = elasticsearchClient.get(
                    g -> g.index(INDEX).id(String.valueOf(spuId)), ProductSearchDoc.class);
            return response.found() ? response.source() : null;   // 不存在 ⇒ null（是结论，不是故障）
        } catch (Exception e) {
            // ⚠️ 与 findById 的刻意差别：ES 不可达时**必须炸**，不能返回 null
            //    （null 的含义是"索引里没有这篇"，与"读不到"是两件事；
            //    把后者伪装成前者会让调用方/运维得出完全相反的结论）。
            throw new IllegalStateException("Elasticsearch 按 id 读取文档失败: " + e.getMessage(), e);
        }
    }

    @Override
    public ProductSearchDoc findById(long spuId) {
        try {
            GetResponse<ProductSearchDoc> response = elasticsearchClient.get(
                    g -> g.index(INDEX).id(String.valueOf(spuId)), ProductSearchDoc.class);
            return response.found() ? response.source() : null;
        } catch (Exception e) {
            log.warn("按 id 读取索引文档失败 spuId={}: {}", spuId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<ProductSearchDoc> searchByTitle(String keyword, int size) {
        try {
            SearchResponse<ProductSearchDoc> response = elasticsearchClient.search(s -> s
                            .index(INDEX)
                            .size(size)
                            .query(q -> q.match(m -> m.field("title").query(keyword))),
                    ProductSearchDoc.class);
            return response.hits().hits().stream()
                    .map(hit -> hit.source())
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("关键字检索失败 keyword={}: {}", keyword, e.getMessage());
            return List.of();
        }
    }

    // ==================== private ====================

    /**
     * 逐页向 product 拉在架商品的索引文档，返回**在架总数**。
     *
     * <p>为什么用"页满就继续"而不是"按 total 死算页数"：product 侧可能在翻页期间有商品上下架
     * （total 会变），按实际返回条数收敛更稳。两道防呆：页不满即停、页数上限。
     *
     * <p>⚠️ <b>"取不满"必须在代码里炸</b>（主 agent 2026-09-14 批准的功能改动，P6-2 §6.1）：
     * 上面的"页不满即停"只说明"翻页到头了"，**不等于"取齐了"**。如果 product 报在架 1000 篇、
     * 实际只给出 800 篇（下游分页 bug / 期间数据变动 / 达到页数上限），原来会**静默继续**，
     * 然后 <b>删掉旧索引、写进一份只有 800 篇的新索引</b> —— 表现是"索引少了 200 篇商品"，
     * 而全流程**没有任何报错**，只能等用户发现"搜不到东西"。
     * 现在：取不满 ⇒ 直接抛 500「文档取不满」，此时**索引还没被碰过**（取数阶段在删索引之前），
     * 与 {@link #reindex()} 里"绝不写半截索引"的承诺一致；运维重新触发一次重建即可（重建是幂等的）。
     */
    private long fetchAllDocs(List<ProductSearchDoc> sink) {
        long onShelfTotal = -1;
        for (long page = 1; page <= REINDEX_MAX_PAGES; page++) {
            IndexDocsResult result = productIndexDocClient.page(page, REINDEX_PAGE_SIZE);
            onShelfTotal = result.totalInShelf();
            sink.addAll(result.docs());
            if (result.size() < REINDEX_PAGE_SIZE) {
                break;
            }
            if (page == REINDEX_MAX_PAGES) {
                log.warn("索引重建取数达到页数上限 {}（已取 {} 条，product 报在架 {} 条），停止翻页",
                        REINDEX_MAX_PAGES, sink.size(), onShelfTotal);
            }
        }
        if (onShelfTotal < 0) {
            // 一页都没拉到（product 返回空页）时 total 仍应 >= 0；这里是防御性兜底
            onShelfTotal = sink.size();
        }
        if (sink.size() < onShelfTotal) {
            // ⚠️ 这里**不带**"商品索引重建失败："前缀：调用方 reindex() 的 catch 已经加了，
            //    两处都写会变成"商品索引重建失败：商品索引重建失败：文档取不满(2/5)"（我第一版就是这样，
            //    从原始日志里看出来的）。最终文案 = 商品索引重建失败：文档取不满（2/5）
            throw new BusinessException(500, "文档取不满（" + sink.size() + "/" + onShelfTotal + "）");
        }
        return onShelfTotal;
    }

    /**
     * 探测标题分词器：smartcn(官方中文分词插件，词典分词) → cjk(内置二元切分) → standard(逐字)。
     * 插件装好后**重启 ES** 即自动升级为 smartcn，无需改代码。
     */
    private String resolveTitleAnalyzer() {
        String cached = resolvedTitleAnalyzer;
        if (cached != null) {
            return cached;
        }
        List<String> candidates = new ArrayList<>();
        if (StringUtils.hasText(configuredTitleAnalyzer) && !"auto".equalsIgnoreCase(configuredTitleAnalyzer)) {
            candidates.add(configuredTitleAnalyzer.trim());
        }
        candidates.addAll(List.of("smartcn", "cjk", "standard"));
        for (String candidate : candidates) {
            if (analyzerAvailable(candidate)) {
                log.info("商品索引标题分词器 = {}（候选：{}；配置：{}）", candidate, candidates, configuredTitleAnalyzer);
                resolvedTitleAnalyzer = candidate;
                return candidate;
            }
        }
        resolvedTitleAnalyzer = "standard";
        return "standard";
    }

    private boolean analyzerAvailable(String analyzer) {
        try {
            elasticsearchClient.indices().analyze(a -> a.analyzer(analyzer).text("蓝牙耳机降噪"));
            return true;
        } catch (Exception e) {
            log.debug("分词器不可用 {}：{}", analyzer, e.getMessage());
            return false;
        }
    }

    /**
     * 条件查询(与原 MySQL 货架 SQL 语义对齐)：
     * <ul>
     *   <li>关键字：{@code match_phrase} on title —— 逼近原来的 {@code LIKE '%kw%'}；
     *       当前 smartcn 分词下短语匹配≈连续子串匹配</li>
     *   <li>类目：调用方已用 CategoryScopeResolver 展开"含子类"，这里是 terms 精确匹配</li>
     *   <li>价格：原 SQL 的 {@code NOT EXISTS(price<min)} / {@code EXISTS(price<=max)} 等价于
     *       {@code minPrice >= q.minPrice} / {@code minPrice <= q.maxPrice}(因为 minPrice 就是该 SKU 集合的最低价)</li>
     *   <li>status=1 双保险(索引本身只放上架商品)</li>
     * </ul>
     */
    private Query buildQuery(String keyword, List<Long> categoryIds, Long brandId, Long minPrice, Long maxPrice) {
        return Query.of(q -> q.bool(b -> {
            if (StringUtils.hasText(keyword)) {
                b.must(m -> m.matchPhrase(mp -> mp.field("title").query(keyword)));
            }
            if (categoryIds != null && !categoryIds.isEmpty()) {
                List<FieldValue> values = categoryIds.stream().map(id -> FieldValue.of(id)).toList();
                b.filter(f -> f.terms(t -> t.field("categoryId").terms(tv -> tv.value(values))));
            }
            if (brandId != null) {
                b.filter(f -> f.term(t -> t.field("brandId").value(brandId)));
            }
            if (minPrice != null) {
                b.filter(f -> f.range(r -> r.number(n -> n.field("minPrice").gte(minPrice.doubleValue()))));
            }
            if (maxPrice != null) {
                b.filter(f -> f.range(r -> r.number(n -> n.field("minPrice").lte(maxPrice.doubleValue()))));
            }
            // 与单体逐字一致：用常量而不是字面量 1（自持副本见 support/constant/EnableStatus）
            b.filter(f -> f.term(t -> t.field("status").value(EnableStatus.ENABLED)));
            return b;
        }));
    }

    /** 排序与前台货架一致；一律用 spuId 兜底保证分页稳定(与 SQL 的 ", id ASC" 对应) */
    private List<SortOptions> buildSort(String sort) {
        String s = sort == null ? "default" : sort;
        return switch (s) {
            case "priceAsc" -> List.of(sortBy("minPrice", SortOrder.Asc), sortBy("spuId", SortOrder.Asc));
            case "priceDesc" -> List.of(sortBy("minPrice", SortOrder.Desc), sortBy("spuId", SortOrder.Asc));
            case "newest" -> List.of(sortBy("createTimeMillis", SortOrder.Desc), sortBy("spuId", SortOrder.Asc));
            default -> List.of(sortBy("sales", SortOrder.Desc), sortBy("spuId", SortOrder.Asc));
        };
    }

    private SortOptions sortBy(String field, SortOrder order) {
        return SortOptions.of(so -> so.field(f -> f.field(field).order(order)));
    }

    private boolean indexExists() throws Exception {
        return elasticsearchClient.indices().exists(e -> e.index(INDEX)).value();
    }

    /** 删除并重建索引(含显式映射：字段类型决定后续能怎么筛/排) */
    private void recreateIndex() {
        String titleAnalyzer = resolveTitleAnalyzer();
        try {
            if (indexExists()) {
                elasticsearchClient.indices().delete(d -> d.index(INDEX));
            }
            try {
                createIndex(titleAnalyzer);
            } catch (Exception createError) {
                // 并发兜底：删除与创建之间，可能有单条同步(syncProduct)先一步把索引建出来
                // （ES 对不存在的索引会自动创建 + 动态映射，映射就错了）。这里再删一次重建，
                // 保证最终存在的一定是**带正确映射**的索引。
                log.warn("索引创建冲突({})，删除后重建以保证映射正确", createError.getMessage());
                if (indexExists()) {
                    elasticsearchClient.indices().delete(d -> d.index(INDEX));
                }
                createIndex(titleAnalyzer);
            }
            indexEnsured = true;
        } catch (Exception e) {
            throw new IllegalStateException("重建 Elasticsearch 索引失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保索引存在且映射正确（单条同步前的自检）。
     *
     * <p>⚠️ 诚实标注：**单体里这个方法（以及 {@code indexEnsured} 的置位）没有调用点**——
     * 现状的 {@code syncProduct} 并不做这个自检（ES 会为不存在的索引自动创建 + 动态映射）。
     * 这里**逐字保留**（含这段说明）而不是顺手删掉：本批的纪律是"ES 逻辑逐字保留、只改取数"，
     * 去掉死代码属于行为变更，应当和"要不要在单条同步前补上这个自检"一起在 P6-5 决定。
     */
    private void ensureIndex() {
        if (indexEnsured) {
            return;
        }
        synchronized (this) {
            if (indexEnsured) {
                return;
            }
            try {
                if (!indexExists()) {
                    createIndex(resolveTitleAnalyzer());
                    log.info("商品索引不存在，已按显式映射创建: {}", INDEX);
                }
                indexEnsured = true;
            } catch (Exception e) {
                log.warn("商品索引自检/创建失败(本次同步将直接尝试写入): {}", e.getMessage());
            }
        }
    }

    /**
     * 按显式映射创建索引。
     *
     * <p>⚠️ 与现状**逐字一致**（12 个字段；{@code title} 的 analyzer 与 searchAnalyzer 都设为探测值，
     * ES 在两者相同时只在 mapping 里序列化 {@code analyzer} 一个字段——实测线上就是
     * {@code {"type":"text","analyzer":"smartcn"}}；{@code subtitle} 纯 text；
     * {@code mainImage} 是 {@code keyword} 且 {@code index:false}）。
     */
    private void createIndex(String titleAnalyzer) throws Exception {
        elasticsearchClient.indices().create(c -> c
                .index(INDEX)
                // 单节点：1 分片 0 副本(配副本会让健康度变黄)
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(m -> m
                        .properties("spuId", p -> p.long_(l -> l))
                        // 中文分词：smartcn/cjk 自动探测(见 resolveTitleAnalyzer)
                        .properties("title", p -> p.text(t -> t
                                .analyzer(titleAnalyzer).searchAnalyzer(titleAnalyzer)))
                        .properties("subtitle", p -> p.text(t -> t))
                        .properties("brandId", p -> p.long_(l -> l))
                        .properties("brandName", p -> p.keyword(k -> k))
                        .properties("categoryId", p -> p.long_(l -> l))
                        .properties("minPrice", p -> p.long_(l -> l))
                        .properties("sales", p -> p.integer(i -> i))
                        .properties("totalStock", p -> p.integer(i -> i))
                        .properties("status", p -> p.integer(i -> i))
                        .properties("mainImage", p -> p.keyword(k -> k.index(false)))
                        .properties("createTimeMillis", p -> p.long_(l -> l))));
    }
}
