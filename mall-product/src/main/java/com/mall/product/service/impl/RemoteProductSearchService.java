package com.mall.product.service.impl;

import com.mall.product.client.SearchProductsClient;
import com.mall.product.dto.ProductIdPage;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.dto.SearchStatusVO;
import com.mall.product.service.ProductSearchService;
import com.mall.product.support.ApiResponse;
import com.mall.product.support.BusinessException;
import com.mall.product.support.CacheService;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.mq.ProductSyncPublisher;
import com.mall.product.support.dto.ReindexResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * <b>检索（关键字分支）的远程实现</b>（P6-3）：把检索交给 {@code mall-search}，
 * 检索不可用时**回落**到"索引不可用"的语义，让 {@code ProductPortalServiceImpl} 走它既有的 MySQL LIKE 分支。
 *
 * <h2>① 只有这一条装配路径</h2>
 * 本类是上下文中**唯一**的 {@link ProductSearchService} bean（{@code @Primary} 是双保险：
 * 万一将来有人把 {@link SearchUnavailableProductSearchService} 又注册成 bean，注入点也仍然拿到本类，
 * 不会出现"两个实现谁能赢"的模糊态）。降级体是**本类内部持有**的对象（不是另一个候选实现）。
 *
 * <h2>② 三种失败必须都回落，而且都不能抛给用户</h2>
 * 规格 §2.1 要求"服务不可达 / 超时 / 返回非 0 业务码"三种情形都回落：
 * <pre>
 * 情形            本类的处理                                                      后果
 * 服务不可达      debug 记原因 + 抛 IllegalStateException（消息里带本次原因）        调用方 catch → MySQL LIKE
 * 超时(300/2500)  同上                                                            同上
 * 非 0 业务码     同上（消息里带 code 与 message）                                  同上
 * </pre>
 * <p>⚠️ **WARN 只由调用方记一条**（规格 §3.3：一次请求"有且仅有一条 WARN"，不要每次降级刷两行）：
 * 本类把原因**拼进异常消息**（调用方那条 WARN 会把它打出来），自己只记 debug。
 * <p>⚠️ **为什么这里是"抛"而不是"返回空结果"**：调用方
 * {@code ProductPortalServiceImpl#searchByEs} 的结构是"**只有异常才回落**"：
 * <pre>
 *   try { ...return 结果; } catch (Exception e) { log.warn("ES 检索失败，降级为 MySQL LIKE: ..."); return null; }
 * </pre>
 * 返回 `total=0` 的空结果会被当成"关键字确实没命中"直接给前端 —— 那正是
 * "索引不可用被伪装成没有数据"（`SearchUnavailableProductSearchService` 的类注释 ③ 已详述）。
 *
 * <h2>③ 运维/同步方法：有的转发远程，有的**如实说明做不到**</h2>
 * <pre>
 * 方法              本类行为                                              原因
 * count()           走 /status 的 docCount；调用失败 → -1                  与现状同口径（-1=不可用）
 * pendingCount()    走 /status 的 pendingCount；失败 → -1
 * titleAnalyzer()   走 /status；失败 → "standard"（照抄单体探测全失败的兜底值）
 * reindex()         转发 /reindex；失败/非 0 ⇒ **抛 BusinessException(500)**  单体在 ES 失败时就是抛 500
 * syncProduct()     转发 /sync/{id}；失败 ⇒ false（不抛）                    照现状：索引同步失败不阻塞商品写
 * deleteProduct()   转发 DELETE；失败 ⇒ 只记 warn                            照现状（幂等）
 * syncByBrand()     转发 /sync-by-brand/{id}；失败 ⇒ 0                       照现状
 * syncLater()       转发 /sync/{id} ⇒ "改完就能搜到"                          见 ④
 * syncBrandLater()  投 MQ（异步）；失败回落本地标记                            见 ④
 * markDirty()       投 MQ（异步，提交后）；失败回落本地 Redis 兜底集合           P6-5 #1
 * drainPending()    返回空列表（不取）                                       那条队列的消费者不是本服务
 * findById()        null；searchByTitle() 空列表                             search **没有**对应内部端点（P6-5 #5 待补）
 * </pre>
 *
 * <h2>④ 两处曾上报的语义差（P6-5 #1 已闭合）</h2>
 * <ol>
 *   <li>{@code markDirty}：单体是"投 MQ（亚秒级）→ 失败回落 Redis"。P6-2 期本服务**只能**写 Redis
 *       （键 {@code mall:es:pending}，与单体/search 逐字相同）⇒ 延迟被 search 的 15s 周期钉死。
 *       <b>P6-5 #1 已改</b>：本服务自己发布到既有 {@code mall.pms.sync} 拓扑（{@link ProductSyncPublisher}），
 *       Redis 集合降级为兜底 —— 与单体逐字同形。</li>
 *   <li>{@code syncBrandLater}：原先**不**转发（search 侧逐篇 {@code refresh(WaitFor)} ≈1.27s，45 篇 ≈57s，
 *       会把后台改名请求卡到分钟级），只能本地标记等 15s。<b>P6-5 #1 已改</b>：异步投 MQ，
 *       由 search 消费者执行；P6-5 #3/#4 把那边改成"批量 + 单次 refresh"后不再逐篇 1.27s。
 *       兜底仍是本地标记（不阻塞请求）。</li>
 * </ol>
 * ⚠️ 三条同步入口（{@code markDirty}/{@code syncLater}/{@code syncBrandLater}）现在都以 **MQ 为主、
 * 本地降级为辅**；"事务提交后才投递"由 {@link ProductSyncPublisher} 保证（本类的库存/销量调用方是在
 * 事务内调过来的）—— 这是 P6-4 幽灵索引文档那类缺陷的正解，不要在调用方再各写一套。
 */
@Slf4j
@Service
@org.springframework.context.annotation.Primary
public class RemoteProductSearchService implements ProductSearchService {

    private final SearchProductsClient client;

    /** 降级体（"索引不可用"语义）：**本类内部持有**，不是另一个候选实现 */
    private final SearchUnavailableProductSearchService degraded;

    /** 索引同步的发布方（P6-5 #1）：MQ 主通道；不可用时回落 {@link #degraded}（Redis 待同步集合） */
    private final ProductSyncPublisher syncPublisher;

    public RemoteProductSearchService(SearchProductsClient client, CacheService cacheService, SpuMapper spuMapper,
                                      ProductSyncPublisher syncPublisher) {
        this.client = client;
        this.degraded = new SearchUnavailableProductSearchService(cacheService, spuMapper);
        this.syncPublisher = syncPublisher;
    }

    // ==================================================================
    // 检索（关键字分支）：唯一会"抛"的方法（见类注释 ②）
    // ==================================================================

    @Override
    public ProductIdPage search(String keyword, List<Long> categoryIds, Long brandId,
                                Long minPrice, Long maxPrice, String sort, long pageNum, long pageSize) {
        ApiResponse<ProductIdPage> response;
        try {
            response = client.searchProducts(keyword, categoryIds, brandId, minPrice, maxPrice, sort, pageNum, pageSize);
        } catch (SearchProductsClient.SearchRemoteException e) {
            // 情形①②：不可达 / 超时（也可能是空响应）
            return unavailable(keyword, "远程调用失败: " + e.getMessage());
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 情形③：返回了响应但业务码非 0
            return unavailable(keyword, "下游业务码非 0: code=" + response.getCode()
                    + " message=" + response.getMessage());
        }
        if (response.getData() == null) {
            return unavailable(keyword, "下游返回 data=null");
        }
        return response.getData();
    }

    /**
     * 统一走降级：**语义委托**给 {@link SearchUnavailableProductSearchService#search}
     * （同一个异常类型、同一句"索引不可用"措辞、同一条"调用方应回落 MySQL LIKE"的指引），
     * 但**把失败原因拼进消息里** —— 这样：
     * <ol>
     *   <li>调用方 {@code ProductPortalServiceImpl} 那条 WARN 就能看出"为什么降级"
     *       （不可达 / 超时 / 非 0 码），运维不必再去翻别的日志；</li>
     *   <li>规格 §3.3 要求"一次请求**有且仅有一条 WARN**"：本方法**不自己记 WARN**（记 debug），
     *       于是全链路只在调用方那一条 warn 上留痕，不会每次降级刷两行。</li>
     * </ol>
     */
    private ProductIdPage unavailable(String keyword, String reason) {
        log.debug("检索不可用，委托降级语义: keyword={} reason={}", keyword, reason);
        // 调用降级体拿到它的"标准措辞"，再把原因附上（保持措辞单一来源，又带上本次的具体原因）
        IllegalStateException signal;
        try {
            return degraded.search(keyword, null, null, null, null, null, 0, 0);
        } catch (IllegalStateException e) {
            signal = e;
        }
        throw new IllegalStateException(signal.getMessage() + "；本次原因：" + reason, signal);
    }

    // ==================================================================
    // 自检：走 /status；不可用时与降级体同值
    // ==================================================================

    @Override
    public long count() {
        SearchStatusVO status = statusOrNull();
        return status == null ? degraded.count() : status.docCount();
    }

    @Override
    public long pendingCount() {
        SearchStatusVO status = statusOrNull();
        return status == null ? degraded.pendingCount() : status.pendingCount();
    }

    @Override
    public String titleAnalyzer() {
        SearchStatusVO status = statusOrNull();
        return status == null ? degraded.titleAnalyzer() : status.titleAnalyzer();
    }

    private SearchStatusVO statusOrNull() {
        try {
            ApiResponse<SearchStatusVO> response = client.status();
            if (response.getCode() != ApiResponse.SUCCESS || response.getData() == null) {
                log.warn("检索自检返回失败: code={} message={}", response.getCode(), response.getMessage());
                return null;
            }
            return response.getData();
        } catch (Exception e) {
            log.warn("检索自检不可用: {}", e.getMessage());
            return null;
        }
    }

    // ==================================================================
    // 运维：reindex 失败要**抛**（与单体在 ES 失败时一致）
    // ==================================================================

    @Override
    public ReindexResult reindex() {
        ApiResponse<ReindexResult> response;
        try {
            response = client.reindex();
        } catch (Exception e) {
            log.warn("索引重建调用失败: {}", e.getMessage());
            throw new BusinessException(500, "商品索引重建失败：" + e.getMessage());
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 下游已经是"明确报错、不写半截索引"的语义（P6-2 §6），这里原样透传它的文案
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }

    // ==================================================================
    // 写索引：一律 fail-open（不抛），与现状一致
    // ==================================================================

    @Override
    public boolean syncProduct(long spuId) {
        try {
            ApiResponse<Boolean> response = client.syncProduct(spuId);
            if (response.getCode() != ApiResponse.SUCCESS) {
                log.warn("索引同步返回业务失败: spuId={} code={} message={}",
                        spuId, response.getCode(), response.getMessage());
                return false;
            }
            return Boolean.TRUE.equals(response.getData());
        } catch (Exception e) {
            log.warn("索引同步失败 spuId={}（可稍后全量重建）: {}", spuId, e.getMessage());
            return false;
        }
    }

    @Override
    public void deleteProduct(long spuId) {
        try {
            ApiResponse<Void> response = client.deleteProduct(spuId);
            if (response.getCode() != ApiResponse.SUCCESS) {
                log.warn("索引删除返回业务失败: spuId={} code={} message={}",
                        spuId, response.getCode(), response.getMessage());
            }
        } catch (Exception e) {
            log.warn("索引删除失败 spuId={}: {}", spuId, e.getMessage());
        }
    }

    @Override
    public int syncByBrand(long brandId) {
        try {
            ApiResponse<Integer> response = client.syncByBrand(brandId);
            if (response.getCode() != ApiResponse.SUCCESS || response.getData() == null) {
                log.warn("按品牌同步失败: brandId={} code={} message={}",
                        brandId, response.getCode(), response.getMessage());
                return 0;
            }
            return response.getData();
        } catch (Exception e) {
            log.warn("按品牌同步失败 brandId={}: {}", brandId, e.getMessage());
            return 0;
        }
    }

    // ==================================================================
    // 同步入口（"改完就能搜到"）+ 队列
    // ==================================================================

    @Override
    public void syncLater(long spuId) {
        // 🆕 P6-5 #1：MQ 可用 ⇒ **异步**投递（后台写商品请求不再受 search 侧 ES 写超时的拖累，
        //   与单体"Mq 开启时异步投递"同形）；MQ 关闭/投递失败 ⇒ 回落"直接转发单条同步"（改完就能搜到）。
        // ⚠️ 单条是 sub-second 级（实测 search 侧每篇 refresh(WaitFor) ≈1.27s），最坏多等 ~1.3s，但**结果**一致。
        // ⚠️ 兜底里再失败 ⇒ 写 Redis 待同步集合（不阻塞后台写操作）。
        syncPublisher.publishSpuIds(List.of(spuId), () -> {
            if (!syncProduct(spuId)) {
                log.warn("单条同步未成功，转为标记待同步(兜底通道): spuId={}", spuId);
                degraded.syncLater(spuId);
            }
        });
    }

    @Override
    public void syncBrandLater(long brandId) {
        // 🆕 P6-5 #1（同时解决本类注释 ④ 第 2 条）：MQ 可用 ⇒ 异步投递"按品牌同步"，
        //   由 search 的消费者执行（P6-5 #3/#4 把那边改成"批量 + 单次 refresh"后不再逐篇 1.27s）。
        //   后台"改品牌名"请求因此**不再**被 ES 写阻塞（原先要么本地标记等 15s、要么阻塞到分钟级）。
        //   兜底：本地标记（枚举该品牌在架 spuId → 写 Redis），仍然不阻塞请求。
        syncPublisher.publishBrand(brandId, () -> degraded.syncBrandLater(brandId));
    }

    @Override
    public void markDirty(Collection<Long> spuIds) {
        // 🆕 P6-5 #1：恢复"MQ 主 + Redis 兜底"（与单体 ProductSearchServiceImpl#markDirty 逐字同语义）。
        //   本类原先是"只能写本地 Redis 待同步集合"⇒ 延迟被 search 的 15s 周期钉死（单体是亚秒级）。
        //   ⚠️ 调用方（StockCommandServiceImpl 的 reserve/release/incrementSales）是在**事务内**调过来的，
        //      所以"提交后再投递"这条由 ProductSyncPublisher 兜住（否则消费方会读到提交前的旧库存，
        //      把旧值写进索引 —— 就是 P6-4 窗口实测的幽灵文档同一类缺陷）。
        //   ⚠️ 兜底仍是本地 Redis 集合：该键正是 search 的 ProductSearchSyncTask 在消费的键，标记不会丢。
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        List<Long> ids = spuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        syncPublisher.publishSpuIds(ids, () -> degraded.markDirty(ids));
    }

    @Override
    public List<Long> drainPending(int max) {
        // 那条队列的消费者是**单体/search 的定时任务**，不是本服务；顺手取走等于偷任务（P6-1 已论证）。
        return degraded.drainPending(max);
    }

    // ==================================================================
    // 仅供测试/排查：search 没有对应内部端点 ⇒ 如实返回"读不到"
    // ==================================================================

    @Override
    public ProductSearchDoc findById(long spuId) {
        // 🆕 P6-5 #5：search 侧补上了 `GET /internal/v1/search/product/{spuId}` ⇒ 这个空洞可以填了。
        //   以前这里"如实返回 null"是因为**没有端点可调**（不是"读不到也装作没有"），现在有端点，
        //   就必须真的去读 —— 否则注释会变成"看起来接了其实没接"。
        //   ⚠️ 语义边界（与 SearchProductsClient#productDoc 的注释同一条）：
        //     · code=0 且 data=null ⇒ 索引里**确实没有**这篇（下架/删除/未入索引）；
        //     · 读不到（不可达/超时/下游非 0 码）⇒ **抛**（本方法没有生产调用方，抛出去比"假装不存在"安全：
        //       把"读不到"说成"没有"会让调用方做出方向相反的判断）。
        ApiResponse<ProductSearchDoc> response;
        try {
            response = client.productDoc(spuId);
        } catch (SearchProductsClient.SearchRemoteException e) {
            throw new IllegalStateException("读取索引文档失败(检索域不可达): spuId=" + spuId + " 原因=" + e.getMessage(), e);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new IllegalStateException("读取索引文档失败(下游业务码非 0): spuId=" + spuId
                    + " code=" + response.getCode() + " message=" + response.getMessage());
        }
        return response.getData();
    }

    @Override
    public List<ProductSearchDoc> searchByTitle(String keyword, int size) {
        // ⚠️ 仍然**没有**实现，理由与上面不同（别再读成"忘了接"）：
        //   search 侧的两个端点里，`/products` 只返回 **spuId 列表 + 总数**（不含文档），
        //   `/product/{spuId}` 只按 id 取单文档 ⇒ 想按标题拿"文档列表"只能"先用 /products 拿 id 再逐个取文档"，
        //   那是 N+1 次跨服务调用，而且**本方法在 product 侧没有任何生产调用方**
        //   （grep 过：只有接口、旧降级实现与测试）。为它造一条 N+1 通道属于"新增能力"，不是补空洞。
        //   真需要时（例如后台"按标题找索引文档"排查工具）应当由 search 侧提供批量取文档的端点，届时再实现。
        log.debug("searchByTitle 未实现（search 无「按标题取文档」端点，且本方法无生产调用方）: keyword={}", keyword);
        return List.of();
    }
}
