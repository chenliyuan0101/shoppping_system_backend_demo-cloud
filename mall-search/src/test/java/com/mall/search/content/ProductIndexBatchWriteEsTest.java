package com.mall.search.content;

import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.util.ObjectBuilder;
import com.jayway.jsonpath.JsonPath;
import com.mall.search.dto.IndexDocsResult;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.BusinessException;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>批量写入语义套件（P6-5 #3/#4 + 批量路径收口的验收落点）</b>：一条批量路径必须
 * "**整批 bulk + 恰好一次 refresh**"，而"恰好一次刷新"同时带来两个必须成立的性质：
 * <b>语义不变</b>与<b>代价不随篇数线性增长</b>。
 *
 * <p>覆盖两条批量入口：{@code syncByBrand}（品牌）与 {@code syncProducts}（MQ 消费 / Redis 兜底 drain
 * 的批次入口 —— 2026-09-14 收口：消费者与定时任务不再逐条调用单篇路径）。
 *
 * <h2>为什么这些断言是"第二条独立判据"而不是重复</h2>
 * 只断言"返回值对了"是抓不到写放大的 —— 逐篇 {@code refresh(WaitFor)} 的旧实现返回值也完全正确，
 * 只是 45 篇要 **60.9s**（P6-5 活体基线实测）。所以本套件同时钉三件事：
 * <ol>
 *   <li><b>往返次数</b>：对 {@code ElasticsearchClient} 上真实发生的调用计数（spy）——
 *       整批只允许 **1 次 {@code bulk}** 与 **1 次 {@code indices()}（= refresh）**。
 *       这条一旦回到"逐篇 index(..., refresh=WaitFor)"就会红：那会是 N 次 {@code index}、0 次 {@code bulk}。
 *       ⚠️ 只数这两个方法名是**为了判据无竞态**：同 JVM 里还有一个 15s 一跳的兜底 drain 任务会走
 *       单篇 {@code index} 路径（见 {@code ProductSearchSyncTask}），把它算进来会让判据偶发假红；
 *       而 {@code bulk}/{@code indices} 只有本批量路径会调（{@code reindex} 也调，但用例之间不并发）；</li>
 *   <li><b>可见性（语义不变）</b>：调用**返回的那一刻**（不 sleep、不重试）用 ES {@code _count} 查这批文档
 *       —— {@code _count} **不是实时的**，它能看到就说明那次 refresh 真的发生了
 *       （这正是"调用返回 = 已可检索"这条承诺的探针；刷新被删掉的话这里必红）；</li>
 *   <li><b>可检索</b>：再走一次**服务内**的关键字检索（{@code POST /products} → ES search），
 *       确认这批内容对真正的检索链路立即可见（不是只有 {@code _count} 看得见）。</li>
 * </ol>
 *
 * <h2>⚠️ 本套件不验什么</h2>
 * 它**不验跨进程的耗时**（那是活体脚本 {@code .dsh-notes/p6-5-writeamp-measure.ps1} 的
 * {@code -Mode brand} / {@code -Mode drain} / {@code -Mode mq}：对着真在跑的 8103 + 真 broker 量秒数）。
 * 用例里的篇数是 5 / 45 两种，是为了把"往返次数"这个**结构性**判据变得确定
 * （逐篇实现会从 1 次变 N 次），而不是靠计时（计时在共享 ES 上必然偶发）。
 *
 * <h2>数据隔离</h2>
 * 假 spuId 号段（{@code 9_100_000_000+}），用例前后各清一次，收尾断言"假号段清零"——
 * 与 {@code ProductIndexContentStubbedEsTest} / {@code ProductIndexIncrementFallbackEsTest} 同一套纪律。
 */
class ProductIndexBatchWriteEsTest extends SearchTestBase {

    /** 批量路径的入口是"按品牌同步"（{@code syncByBrand}），品牌号随便给（内容源已打桩） */
    private static final long BRAND_ID = 937L;

    private static final int BATCH_SIZE = 5;

    /** 每轮唯一的检索词元（与增量套件同一个手法：只可能命中本用例造的那几篇） */
    private final String marker = "zzbatch" + suffix;

    private final List<Long> spuIds = new ArrayList<>();

    /** 被测对象（**真实实现**，只把出站内容源换成基类提供的桩） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.mall.search.service.ProductSearchService productSearchService;

    /**
     * ES 客户端的 **spy**：本套件的核心仪器（**只用来数调用次数**，不做任何打桩——
     * 所有方法仍然走真 ES，所以"批量写入真的生效了"这件事还是被真验的）。
     */
    @MockitoSpyBean
    private co.elastic.clients.elasticsearch.ElasticsearchClient esSpy;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            spuIds.add(FAKE_SPU_ID_BASE + 600 + i);
        }
        cleanOwnDocs();
    }

    @AfterEach
    void cleanUp() {
        cleanOwnDocs();
        for (Long spuId : spuIds) {
            assertNull(getDoc(spuId), "用例遗留了文档 " + spuId);
        }
        assertEquals(0L, fakeSegmentDocCount(), "假号段（9 开头）必须清零，不许给共享索引留垃圾");
    }

    @Test
    @DisplayName("[批量/写放大] syncByBrand：整批 1 次 bulk + **恰好 1 次** refresh，且返回即可检索（语义不变）")
    void syncByBrand_isOneBulkAndOneRefresh_andImmediatelyVisible() throws Exception {
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, batchDocs()));

        // 只数"本次调用"的往返：spy 上已经记着上下文启动/前置守卫的调用，先清零
        Mockito.clearInvocations(esSpy);

        int synced = productSearchService.syncByBrand(BRAND_ID);

        assertEquals(BATCH_SIZE, synced, "五篇都应当写入成功");
        assertEquals(1, bulkCallCount(),
                "整批只允许一次 bulk —— 变成逐篇写入时这里会是 0（走的是 index），"
                        + "而那正是 45 篇 ≈60.9s 的成因");
        assertEquals(1, invocationCount("indices"),
                "整批只允许一次 indices()（= refresh）—— 逐篇 refresh(WaitFor) 的实现这里会是 0，"
                        + "因为刷新挂在 index 请求上、不经过 indices()");
        // ⚠️ 刻意**不**断言 index() 调用次数为 0：本 JVM 里还有一个 15s 一跳的后台任务
        //    （ProductSearchSyncTask 的兜底 drain）会走单篇 index 路径，把它算进来会让判据偶发假红。
        //    "是不是逐篇写入"已经被上面的 `bulk == 1` 钉死了（逐篇实现根本不会发 bulk）。

        // 关键：**不做任何等待**，直接查（ES 的 _count 不是实时的：能看到就证明那次 refresh 真的发生了）
        assertEquals(BATCH_SIZE, fakeSegmentDocCount(),
                "调用返回的那一刻，这一批必须已经可见（bulk 之后少了那次 refresh 就会看到 0）");

        // 再走一次真正的检索链路（服务内 search → ES search）
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products",
                        "{\"keyword\":\"" + marker + "\",\"pageSize\":20}"))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "检索应当成功，body=" + b);
        assertEquals(BATCH_SIZE, ((Number) JsonPath.read(b, "$.data.total")).intValue(),
                "刚刚批量同步的这一批必须**立刻**可检索（这就是'调用返回 = 已可检索'这条承诺）");
    }

    @Test
    @DisplayName("[批量/幂等] 同一批连续同步两次：仍是一次 bulk + 一次 refresh，且不产生重复文档")
    void syncByBrand_isIdempotentAcrossRuns() throws Exception {
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, batchDocs()));
        assertEquals(BATCH_SIZE, productSearchService.syncByBrand(BRAND_ID));

        // 内容变了（销量 +1）再同步一次：应当**覆盖**而不是新增（_id = spuId）
        List<ProductSearchDoc> updated = batchDocs();
        updated.forEach(doc -> doc.setSales(99));
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, updated));

        Mockito.clearInvocations(esSpy);
        assertEquals(BATCH_SIZE, productSearchService.syncByBrand(BRAND_ID), "第二次也必须全部成功");

        assertEquals(1, bulkCallCount(), "第二次同步同样只允许一次 bulk");
        assertEquals(1, invocationCount("indices"), "第二次同步同样只允许一次 refresh");
        assertEquals(BATCH_SIZE, fakeSegmentDocCount(),
                "同一个 spuId 反复同步只能有 1 篇文档（假号段共 " + BATCH_SIZE + " 篇，不是 " + (BATCH_SIZE * 2) + " 篇）");
        assertEquals(99, getDoc(spuIds.get(0)).getSales(), "重复同步必须按最新内容覆盖");
    }

    @Test
    @DisplayName("[批量/空集] 品牌下没有在架商品 ⇒ 0 条、**一次 ES 写入都不发**（不做无意义的 bulk/refresh）")
    void syncByBrand_emptyBrand_touchesNothing() {
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, List.of()));

        Mockito.clearInvocations(esSpy);

        assertEquals(0, productSearchService.syncByBrand(BRAND_ID), "没有商品可同步 ⇒ 0");
        assertEquals(0, bulkCallCount(), "空批不该发 bulk");
        assertEquals(0, invocationCount("indices"), "空批不该 refresh");
    }

    @Test
    @DisplayName("[批量/耗时] 45 篇（验收 §三 第 1 条的口径）的**进程内实测**秒数，并把改前活体基线一起打印")
    void syncByBrand_45docs_isOneBulkOneRefresh_withinBudget() {
        // 45 篇 = 验收 §三 第 1 条的那个批次规模（改前活体实测 60,906 ms）
        List<ProductSearchDoc> docs = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            ProductSearchDoc doc = docFor(FAKE_SPU_ID_BASE + 800 + i, i);
            docs.add(doc);
            spuIds.add(doc.getSpuId());   // 纳入收尾清理（@AfterEach 逐篇删 + 假号段清零）
        }
        when(productIndexDocClient.byBrand(anyLong())).thenReturn(new IndexDocsResult(1373L, docs));

        Mockito.clearInvocations(esSpy);
        long startNanos = System.nanoTime();
        int synced = productSearchService.syncByBrand(BRAND_ID);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        // 这条打印是**给验收用的原始输出**：活体数字要等架构师重启 8103 后用
        // .dsh-notes/p6-5-writeamp-measure.ps1 量；这里是"新代码 + 真 ES"的**进程内**数字。
        System.out.println("[P6-5 写放大] syncByBrand 45 篇进程内实测 = " + elapsedMs
                + " ms（bulk 调用=" + bulkCallCount() + "，refresh 调用=" + invocationCount("indices") + "）"
                + "；改前（部署中的旧 jar、逐篇 refresh(WaitFor)）活体基线 = 60,906 ms");

        assertEquals(45, synced, "45 篇都应当写入成功");
        assertEquals(1, bulkCallCount(), "45 篇只允许一次 bulk");
        assertEquals(1, invocationCount("indices"), "45 篇只允许一次 refresh");
        assertEquals(45, fakeSegmentDocCount(), "45 篇必须在调用返回时已经可见（不 sleep 直接查）");
        // ⚠️ 上限刻意放到 30s（正常是 1~3s 量级）：它只是"又退回逐篇刷新"的**绊线**
        //    （逐篇实测 60.9s），不是性能断言 —— 在共享 ES 上比谁快会变成偶发红。
        assertTrue(elapsedMs < 30_000L,
                "45 篇批量同步耗时 " + elapsedMs + " ms（改前逐篇 refresh(WaitFor) 是 60,906 ms）："
                        + "接近这个量级说明批量路径又回到'每篇等一次刷新'了");
    }

    // ==================================================================
    // ② P6-5 批量路径收口：syncProducts（MQ 消费 / Redis 兜底 drain 的批次入口）
    // ==================================================================

    @Test
    @DisplayName("[收口/45 个 id] syncProducts：整批 1 次 bulk + 恰好 1 次 refresh，返回即可见（消费者/drain 的口径）")
    void syncProducts_45ids_isOneBulkOneRefresh_andImmediatelyVisible() {
        List<Long> ids = new ArrayList<>();
        List<ProductSearchDoc> docs = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            long spuId = FAKE_SPU_ID_BASE + 900 + i;
            ids.add(spuId);
            spuIds.add(spuId);                 // 纳入收尾清理
            docs.add(docFor(spuId, i));
        }
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L, docs));

        Mockito.clearInvocations(esSpy);
        long startNanos = System.nanoTime();
        List<Long> failed = productSearchService.syncProducts(ids);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        System.out.println("[P6-5 批量收口] syncProducts 45 个 id 进程内实测 = " + elapsedMs
                + " ms（bulk 调用=" + bulkCallCount() + "，refresh 调用=" + invocationCount("indices") + "）"
                + "；收口前（消费者逐条 syncProduct，每条一次 refresh）45 条约 45 × 单篇耗时");

        assertEquals(List.of(), failed, "全部成功 ⇒ 失败列表为空");
        assertEquals(1, bulkCallCount(), "45 个 id 只允许一次 bulk（逐条实现这里会是 0，走的是 index）");
        assertEquals(1, invocationCount("indices"), "45 个 id 只允许一次 refresh");
        assertEquals(45, fakeSegmentDocCount(), "返回的那一刻 45 篇必须已经可见（不 sleep 直接查 _count）");
        assertTrue(elapsedMs < 30_000L,
                "45 个 id 的批量同步耗时 " + elapsedMs + " ms（逐条 + 每篇 refresh 的口径是数十秒量级）");
    }

    @Test
    @DisplayName("[收口/下架差集] 内容源没给的 id ⇒ 从索引**删除**且**不算失败**（删不存在的文档也不算失败）")
    void syncProducts_missingIds_areDeletedAndNotCountedAsFailed() {
        long keepA = spuIds.get(0);
        long keepB = spuIds.get(1);
        long removed = spuIds.get(2);
        long neverExisted = FAKE_SPU_ID_BASE + 999;

        // ① 三篇都在架：全部写进索引
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L,
                List.of(docFor(keepA, 0), docFor(keepB, 1), docFor(removed, 2))));
        assertEquals(List.of(), productSearchService.syncProducts(List.of(keepA, keepB, removed)));
        assertEquals(3, fakeSegmentDocCount(), "前置条件：三篇都应当在索引里");

        // ② removed 下架了（内容源不再返回它）⇒ 差集 ⇒ 删除，且**不算失败**
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L,
                List.of(docFor(keepA, 0), docFor(keepB, 1))));
        List<Long> failed = productSearchService.syncProducts(List.of(keepA, keepB, removed));

        assertEquals(List.of(), failed,
                "下架/删除是**同步成功**（与单篇 syncProduct 的'下架也算成功'一致）：把失败报出去会让消费者无谓重试");
        assertNull(getDoc(removed), "内容源没给的 id 必须从索引里删掉");
        assertNotNull(getDoc(keepA), "只应删差集里的那一个");
        assertNotNull(getDoc(keepB));
        assertEquals(2, fakeSegmentDocCount());

        // ③ 删一个**从来没进过索引**的 id：ES 返回 not_found（不是 error）⇒ 同样不算失败
        assertEquals(List.of(), productSearchService.syncProducts(List.of(neverExisted)),
                "删一个本来就不存在的文档是幂等的，不该被记成失败（否则第一次下架的商品会被无限重试）");
    }

    @Test
    @DisplayName("[收口/部分失败] ES bulk 的 per-item 结果映射回 failed：**只有报错的那个 id** 算失败")
    void syncProducts_perItemError_mapsBackToThatOneId() throws Exception {
        long ok1 = spuIds.get(0);
        long bad = spuIds.get(1);
        long ok2 = spuIds.get(2);
        when(productIndexDocClient.bySpuIds(any())).thenReturn(new IndexDocsResult(1373L,
                List.of(docFor(ok1, 0), docFor(bad, 1), docFor(ok2, 2))));

        // 用 spy 伪造一次"3 篇里 1 篇被 ES 拒绝"的 bulk 响应（真 ES 上很难稳定造出 per-item 错误，
        // 而这条路径正是"哪几个 id 该进重试队列"的判据所在 —— 必须能验）
        BulkResponse oneItemFailed = BulkResponse.of(b -> b.took(1).errors(true).items(
                BulkResponseItem.of(i -> i.operationType(OperationType.Index).index(INDEX)
                        .id(String.valueOf(ok1)).status(201).result("created")),
                BulkResponseItem.of(i -> i.operationType(OperationType.Index).index(INDEX)
                        .id(String.valueOf(bad)).status(400)
                        .error(ErrorCause.of(e -> e.type("mapper_parsing_exception").reason("stubbed per-item failure")))),
                BulkResponseItem.of(i -> i.operationType(OperationType.Index).index(INDEX)
                        .id(String.valueOf(ok2)).status(201).result("created"))));
        // ⚠️ 必须用 doReturn(...).when(spy) 的写法：`when(spy.bulk(...))` 会**真的执行**真实方法
        //    （参数是匹配器返回的 null ⇒ 当场 NPE），这是 spy 打桩的经典坑。
        doReturn(oneItemFailed).when(esSpy)
                .bulk(ArgumentMatchers.<Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>>>any());

        Mockito.clearInvocations(esSpy);
        List<Long> failed = productSearchService.syncProducts(List.of(ok1, bad, ok2));

        assertEquals(List.of(bad), failed,
                "只有 bulk item 里带 error 的那个 id 才算失败（另两篇是成功的，不该被拖去重试）");
        assertEquals(1, invocationCount("indices"),
                "即便有 per-item 失败，refresh 仍然恰好一次（失败不该把整批的可见性取消掉）");
    }

    @Test
    @DisplayName("[收口/取内容失败] 内容源不可用 ⇒ **全部按失败**返回（保序/保重复/保 null），且索引一篇不动")
    void syncProducts_contentSourceFails_allIdsFailedInOrder() {
        long a = spuIds.get(0);
        long b = spuIds.get(1);
        when(productIndexDocClient.bySpuIds(any()))
                .thenThrow(new BusinessException(500, "系统繁忙，请稍后重试"));

        Mockito.clearInvocations(esSpy);
        List<Long> failed = productSearchService.syncProducts(Arrays.asList(null, a, a, b));

        assertEquals(Arrays.asList(null, a, a, b), failed,
                "取内容失败 ⇒ 这批全部算失败；且列表**按入参顺序**、保留重复、保留 null "
                        + "（原逐条实现是 `spuId == null || !syncProduct(spuId) → failed.add(spuId)`）");
        assertEquals(0, bulkCallCount(), "取内容阶段就失败：不该碰 ES（索引一篇不动）");
        assertEquals(0, invocationCount("indices"), "取内容阶段就失败：不该 refresh");
        assertEquals(0, fakeSegmentDocCount(), "索引必须一篇都没动");
    }

    @Test
    @DisplayName("[收口/空入参] 空集合 ⇒ 空失败列表且一次 ES 都不碰；全 null ⇒ 每个 null 各算一条失败")
    void syncProducts_emptyOrAllNullInput_touchesNothing() {
        Mockito.clearInvocations(esSpy);

        assertEquals(List.of(), productSearchService.syncProducts(List.of()),
                "空集合 ⇒ 空失败列表（原逐条实现：循环不执行）");
        assertEquals(Arrays.asList(null, null), productSearchService.syncProducts(Arrays.asList(null, null)),
                "全 null ⇒ 每个 null 都是一条失败（与原实现的 null 分支逐字一致）");

        assertEquals(0, bulkCallCount(), "没有真 id 时一次 bulk 都不该发");
        assertEquals(0, invocationCount("indices"), "没有真 id 时一次 refresh 都不该发");
    }

    // ---------- helpers ----------

    /** 造一批"内容源给出的"索引文档（12 字段齐全，标题带本轮唯一词元） */
    private List<ProductSearchDoc> batchDocs() {
        List<ProductSearchDoc> docs = new ArrayList<>();
        for (int i = 0; i < spuIds.size(); i++) {
            docs.add(docFor(spuIds.get(i), i));
        }
        return docs;
    }

    /** 一份 12 字段齐全的索引文档（内容源给什么，这里就造什么） */
    private ProductSearchDoc docFor(long spuId, int index) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spuId);
        doc.setTitle(marker + " 批量写入语料 " + index);
        doc.setSubtitle("批量语料");
        doc.setMainImage("http://img/p6-5/batch.jpg");
        doc.setCategoryId(12L);
        doc.setBrandId(BRAND_ID);
        doc.setBrandName("雪松");
        doc.setMinPrice(19_900L);
        doc.setTotalStock(41);
        doc.setSales(7 + index);
        doc.setStatus(1);
        doc.setCreateTimeMillis(1_789_000_200_000L + index);
        return doc;
    }

    /**
     * spy 上按**方法名**数调用次数（不走 Mockito 的重载匹配，避免 bulk/index 重载歧义）。
     *
     * <p>⚠️ {@code bulk} 要按"**逻辑调用**"数（只数我们传 Function 的那一次）：ES 客户端自己的
     * {@code bulk(Function)} 实现内部会调 {@code this.bulk(request)}，而 spy 走真实方法时 {@code this}
     * 就是 spy ⇒ 一次逻辑调用会被记成 2 条 invocation（实测踩到：断言 {@code ==1} 报的是 2）。
     * 数错了会让"恰好一次 bulk"这条判据变成假红，所以这里显式过滤参数类型。
     */
    private long bulkCallCount() {
        return Mockito.mockingDetails(esSpy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("bulk"))
                .filter(invocation -> invocation.getArguments().length == 1
                        && invocation.getArgument(0) instanceof java.util.function.Function)
                .count();
    }

    /** spy 上按**方法名**数调用次数（{@code indices()} 没有内部自调，直接数即可） */
    private long invocationCount(String methodName) {
        return Mockito.mockingDetails(esSpy).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(methodName))
                .count();
    }

    private void cleanOwnDocs() {
        for (Long spuId : spuIds) {
            deleteDocQuietly(spuId);
        }
    }

    /** 前置守卫：`@MockitoSpyBean` 没生效时立刻红（否则 invocationCount 会抛，信息不清晰） */
    @Test
    @DisplayName("[批量/仪器自检] ES 客户端确实是 spy（探针自身不许假绿）")
    void spyInstrument_isAttached() {
        assertTrue(Mockito.mockingDetails(esSpy).isSpy(),
                "本套件的往返次数判据依赖 @MockitoSpyBean；它不是 spy 就说明仪器没装上（结论不可信）");
        assertNotNull(elasticsearchClient, "被注入的客户端必须存在（spy 只换包装，不换行为）");
    }
}

