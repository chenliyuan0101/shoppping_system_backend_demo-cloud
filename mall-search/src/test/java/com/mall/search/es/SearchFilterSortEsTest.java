package com.mall.search.es;

import com.jayway.jsonpath.JsonPath;
import com.mall.search.dto.ProductSearchDoc;
import com.mall.search.support.SearchTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>检索语义套件</b>：筛选**确实下推到 ES**、四种排序真在 ES 侧生效。
 *
 * <h2>从哪来 / 丢掉了什么（认真读一遍再改本类）</h2>
 * 本套件迁移自单体 {@code pms/ElasticsearchSearchMySqlTest}（筛选 / 排序两节）。单体那版的核心断言是
 * <b>跨引擎等值</b>：
 * <pre>
 *   esPage.total() == spuMapper.countShelf(query)      // ES 命中数 == MySQL LIKE 命中数
 *   $.data.total   == esPage.total()                   // 前台总数 == ES 总数
 *   $.data.list[*].spuId 顺序 == ES 顺序                // 回表后不乱序
 * </pre>
 * 这三条在 mall-search <b>一条都留不下</b>，因为本服务**没有 MySQL**：
 * <ul>
 *   <li>没有对照物 ⇒ 在自造语料上再断言"和 LIKE 一致"只是**自证**，不是验证；</li>
 *   <li>"前台回表后不乱序"是 <b>product</b> 的契约（它才有库），本服务只能验"我返回的 id 已经排好"；</li>
 *   <li>"ES 与 MySQL LIKE 口径一致"这条现在的验证手段是**跨实现对照**（主 agent 已做过：
 *       同一关键词下两边的 spuId 集合逐一相等）。P6-3 把货架读路径接到本服务后，该对照应当成为
 *       P6-3 的验收项 —— **不要**因为这里删掉了就以为它已经被验过（这正是"迁移时悄悄弱化"的典型）。</li>
 * </ul>
 * 所以本类改成"**语料是我灌的，精确答案我自己知道**"：命中哪些 id、按什么顺序，逐个写死。
 * 这比"两个引擎数目相等"更锐利 —— 筛选被忽略、排序没生效这类错误，总数相等是抓不到的。
 *
 * <h2>语料与隔离（**每条查询都必须限定在假号段内**）</h2>
 * 索引是**共享**的（里面还有 1373 篇真实商品），因此除了"筛选条件本身就是假号段"的用例，
 * 其余查询一律带上 {@code corpus()} 这个类目过滤，保证命中集合只有本套件的 6 篇语料。
 * 第一版我漏了这件事（"空条件"的排序用例会把真实商品一起排进来，断言必然错），记在这里。
 *
 * <p>真 ES 不可达时本类**直接红**（继承 {@link SearchTestBase} 的守卫），不静默跳过；
 * 用例自建自删，{@code @AfterEach} 核对"文档数回到基线"。
 */
class SearchFilterSortEsTest extends SearchTestBase {

    /** 假号段：绝不可能与真实 brandId/categoryId 相撞（真实值都是两位数/三位数） */
    private static final long BRAND_A = 9_100_001L;
    private static final long BRAND_B = 9_100_002L;
    private static final long BRAND_C = 9_100_003L;
    private static final long CAT_1 = 9_200_001L;
    private static final long CAT_2 = 9_200_002L;

    /** 语料：序号 / 品牌 / 类目 / 最低价 / 销量 / 上架时间（**各字段的顺序刻意互不相同**） */
    private static final long[][] CORPUS = {
            //  seq   brand      category   minPrice  sales  createTimeMillis
            {101, BRAND_A, CAT_1, 10_000, 2, 1_700_000_001_000L},
            {102, BRAND_A, CAT_1, 20_000, 5, 1_700_000_002_000L},
            {103, BRAND_B, CAT_2, 30_000, 0, 1_700_000_003_000L},
            {104, BRAND_B, CAT_2, 40_000, 4, 1_700_000_004_000L},
            {105, BRAND_C, CAT_1, 50_000, 1, 1_700_000_005_000L},
            {106, BRAND_C, CAT_1, 60_000, 3, 1_700_000_006_000L},
    };

    /** 语料标题（按序号）：关键字用例要拿它去问分词器，证明"命中/不命中"的真实原因 */
    private final java.util.Map<Long, String> titles = new java.util.HashMap<>();

    @BeforeEach
    void indexCorpus() throws Exception {
        List<co.elastic.clients.elasticsearch.core.bulk.BulkOperation> ops = new ArrayList<>();
        for (long[] row : CORPUS) {
            ProductSearchDoc doc = docOf(row);
            titles.put(row[0], doc.getTitle());
            ops.add(co.elastic.clients.elasticsearch.core.bulk.BulkOperation.of(op -> op.index(idx -> idx
                    .index(INDEX).id(String.valueOf(doc.getSpuId())).document(doc))));
        }
        // 灌入后 **refresh(true)**：ES 的 _count/search 都不是实时的，不刷新的话紧随其后的检索
        // 看不到语料 —— 这种"看起来像业务 bug 的时序问题"必须在测试里挡住，不能怪实现
        elasticsearchClient.bulk(b -> b.operations(ops).refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        // 夹具自检：语料真的进去了（bulk 静默失败 / 索引正好被别人删掉重建时，这里立刻报明原因，
        // 而不是让后面的断言报出莫名其妙的"命中数不对"）
        assertEquals(CORPUS.length, fakeSegmentDocCount(),
                "夹具没灌进去：假号段文档数应当等于语料条数 " + CORPUS.length);
    }

    @AfterEach
    void dropCorpus() {
        for (long[] row : CORPUS) {
            deleteDocQuietly(spuId(row[0]));
        }
        // ⚠️ 这里**不做**"全局文档数回到基线"的比较：索引是共享的，05:45 我实测撞上别人的
        //    reindex 窗口（删索引→重建→写 1373 篇），当场看到 docCount=-1，报出来却是"测试留了垃圾"。
        //    换成两条**只关于我自己**的判据：逐篇必须已删除 + 假号段必须清零。比原来更锐利。
        for (long[] row : CORPUS) {
            assertNull(getDoc(spuId(row[0])), "用例遗留了文档 " + spuId(row[0]) + "（必须清干净）");
        }
        assertEquals(0L, fakeSegmentDocCount(),
                "假号段（9 开头）必须清零：本套件的 6 篇语料全在这里，留一篇就说明清理漏了");
    }

    @Test
    @DisplayName("[筛选/品牌] brandId 精确命中该品牌语料；不存在的品牌 0 条（证明筛选真下推）")
    void brandFilter_isPushedDown() throws Exception {
        assertEquals(List.of(102L, 101L), seq(search("{\"brandId\":" + BRAND_A + "}")));
        assertEquals(List.of(104L, 103L), seq(search("{\"brandId\":" + BRAND_B + "}")));
        assertEquals(List.of(106L, 105L), seq(search("{\"brandId\":" + BRAND_C + "}")));
        // 负向对照：筛选被忽略时这里会返回全部语料（甚至全部真实商品），所以这条能抓住"筛选没生效"
        assertEquals(List.of(), seq(search("{\"brandId\":9999999}")), "不存在的品牌必须 0 条");
    }

    @Test
    @DisplayName("[筛选/类目] categoryIds 多值命中；不存在的类目 0 条")
    void categoryFilter_isPushedDown() throws Exception {
        assertEquals(List.of(102L, 106L, 101L, 105L), seq(search("{\"categoryIds\":[" + CAT_1 + "]}")),
                "CAT_1 下有 4 篇（101/102/105/106），default 排序按销量降序 ⇒ 102,106,101,105");
        assertEquals(List.of(104L, 103L), seq(search("{\"categoryIds\":[" + CAT_2 + "]}")),
                "CAT_2 下有 2 篇（销量 104=4 > 103=0）");
        assertEquals(List.of(106L, 105L, 102L, 101L), seq(search("{\"categoryIds\":[" + CAT_1 + "],\"sort\":\"priceDesc\"}")),
                "多值 + 排序组合（CAT_1 按价格降序）");
        assertEquals(List.of(), seq(search("{\"categoryIds\":[9999999]}")), "不存在的类目必须 0 条");
    }

    @Test
    @DisplayName("[筛选/价格] 价格区间是**闭区间**（minPrice >= / <=）；只给一侧也生效")
    void priceRangeFilter_isInclusive() throws Exception {
        assertEquals(List.of(102L, 104L, 103L),
                seq(search(corpus(", \"minPrice\":20000, \"maxPrice\":40000"))),
                "闭区间 [20000,40000] ⇒ 20000/30000/40000 三篇（边界值必须包含）；default 销量降序");
        assertEquals(List.of(104L, 106L, 105L, 103L), seq(search(corpus(", \"minPrice\":30000"))), "只给下界");
        assertEquals(List.of(102L, 101L), seq(search(corpus(", \"maxPrice\":20000"))), "只给上界（default 销量降序）");
        assertEquals(List.of(), seq(search(corpus(", \"minPrice\":61000"))), "高于语料最高价 ⇒ 0 条");
    }

    @Test
    @DisplayName("[排序] 四种排序都在 ES 侧生效：default=销量降序 / 价格升序 / 价格降序 / 最新在前")
    void allFourSorts_areAppliedInEs() throws Exception {
        // 语料里销量顺序(5,4,3,2,1,0 → 102,104,106,101,105,103)与价格顺序刻意不同，
        // 所以"按销量"与"按价格"不可能同时绿 —— 排序没生效时必然有一条红
        assertEquals(List.of(102L, 104L, 106L, 101L, 105L, 103L), seq(search(corpus(""))),
                "default 必须按 sales 降序（spuId 兜底）");
        assertEquals(List.of(101L, 102L, 103L, 104L, 105L, 106L),
                seq(search(corpus(", \"sort\":\"priceAsc\""))), "价格升序");
        assertEquals(List.of(106L, 105L, 104L, 103L, 102L, 101L),
                seq(search(corpus(", \"sort\":\"priceDesc\""))), "价格降序");
        assertEquals(List.of(106L, 105L, 104L, 103L, 102L, 101L),
                seq(search(corpus(", \"sort\":\"newest\""))), "newest 必须按 createTimeMillis 降序");
        assertEquals(List.of(102L, 104L, 106L, 101L, 105L, 103L),
                seq(search(corpus(", \"sort\":\"随便传\""))), "未知排序值必须回落 default（不信任调用方传参）");
    }

    @Test
    @DisplayName("[筛选+排序+总数] 组合条件：品牌 + 价格上界 + 价格升序，total 与 ids 必须自洽")
    void filtersAndSort_combined() throws Exception {
        Page page = search("{\"brandId\":" + BRAND_C + ",\"maxPrice\":55000,\"sort\":\"priceAsc\"}");
        assertEquals(List.of(105L), seq(page), "BRAND_C 下 <=55000 的只有 105（106=60000 必须被排除）");
        assertEquals(1, page.total(), "命中总数必须同步收窄，不许与 ids 对不上");
    }

    @Test
    @DisplayName("[关键字] match_phrase 按**词元**匹配：字面包含但词元不同就不命中（≠ SQL 的 LIKE '%kw%'）")
    void keyword_matchesTokensNotSubstrings() throws Exception {
        // ① 语料里只有 102 的标题含 zzp6alpha、只有 105 含 zzp6beta
        assertEquals(List.of(102L), seq(search("{\"keyword\":\"zzp6alpha\"}")));
        assertEquals(List.of(105L), seq(search("{\"keyword\":\"zzp6beta\"}")));
        // ② **实测**（不是猜的）：smartcn 把 "zzp6alpha" 切成 [zzp, 6, alpha]，
        //    所以 "zzp6" 在文档里是一段**相邻词元序列**，短语匹配照样命中两篇。
        //    —— 我第一版把这里写成"半个词不命中"，跑出来是红的：期待是错的，实现没错。
        assertEquals(List.of(102L, 105L), seq(search("{\"keyword\":\"zzp6\"}")),
                "zzp6 是 [zzp,6] 这段相邻词元 ⇒ 命中；这不是 LIKE 语义，但也**不是**前缀/子串规则");

        // ③ 分词器实测证据（把真实切法打出来，供 P6-3 对照）
        var tokens = elasticsearchClient.indices().analyze(a -> a.index(INDEX).field("title")
                .text(titles.get(102L))).tokens().stream().map(t -> t.token()).toList();
        System.out.println("[ES 分词] smartcn(" + titles.get(102L) + ") = " + tokens);
        assertTrue(tokens.contains("sortcorpu"),
                "实测 smartcn 把 sortcorpus 切成 sortcorpu（词典驱动的怪切法），当前=" + tokens);

        // ④ 与 SQL 的真实差异（**实测**行为，P6-3 接线时必须盯住）：
        //    · "耳机"是词元、"机"不是 ⇒ LIKE '%机%' 会命中的查询，这里命中不了
        assertEquals(List.of(), seq(search(corpus(", \"keyword\":\"机\""))),
                "字面包含 ≠ 词元命中：'耳机'是一个词元，查询'机'是另一个词元 ⇒ 不命中"
                        + "（同样的查询在 MySQL 里是 LIKE '%机%'，会命中）");
        //    · 反向确认：同一批语料里，完整的词元查得到 —— 证明上一条不是"索引里没这些东西"
        assertEquals(6, seq(search(corpus(", \"keyword\":\"sortcorpu\""))).size(),
                "用分词器实际切出的词元查必须 6 篇全中（否则上一条'不命中'说明不了任何问题）");
        //    · ⚠️ 我一度以为"标题字面含 sortcorpus 却不命中"是 LIKE 差异的证据，**实测这条路是错的**：
        //      查询串 sortcorpus 同样被切成同一个词元 sortcorpu ⇒ 照样命中（会话侧与索引侧同一套分析器）。
        //      真正的差异在中文那里（'机' vs '耳机'），不在"字母串看起来像子串"这种地方。
        //      记在这里，免得下一个人（或下一版的我）又拿它当证据。
        assertEquals(6, seq(search(corpus(", \"keyword\":\"sortcorpus\""))).size(),
                "实测：查询串与文档词元被同一套分析器切成同一个词元 ⇒ 命中（这条**不是**子串匹配的证据）");
    }

    // ---------- helpers ----------

    private long spuId(long seq) {
        return FAKE_SPU_ID_BASE + seq;
    }

    private ProductSearchDoc docOf(long[] row) {
        ProductSearchDoc doc = new ProductSearchDoc();
        doc.setSpuId(spuId(row[0]));
        doc.setTitle("p6sortcorpus " + (row[0] == 102 ? "zzp6alpha " : row[0] == 105 ? "zzp6beta " : "")
                + "耳机 " + row[0] + " " + suffix);
        doc.setSubtitle("筛选排序语料");
        doc.setMainImage("http://img/p6-2/sort-" + row[0] + ".jpg");
        doc.setCategoryId(row[2]);
        doc.setBrandId(row[1]);
        doc.setBrandName("假品牌" + row[1]);
        doc.setMinPrice(row[3]);
        doc.setTotalStock(10);
        doc.setSales((int) row[4]);
        doc.setStatus(1);
        doc.setCreateTimeMillis(row[5]);
        return doc;
    }

    /** 只命中本套件语料的过滤条件（两个假类目），用于"条件本身不是假号段"的用例 */
    private String corpus(String extra) {
        return "{\"categoryIds\":[" + CAT_1 + "," + CAT_2 + "]" + extra + "}";
    }

    private Page search(String bodyJson) throws Exception {
        MvcResult r = mockMvc.perform(internalPost("/internal/v1/search/products", bodyJson))
                .andExpect(status().isOk()).andReturn();
        String b = body(r);
        assertEquals(0, codeOf(b), "检索应当成功，body=" + b);
        List<Number> raw = JsonPath.read(b, "$.data.spuIds");
        return new Page(((Number) JsonPath.read(b, "$.data.total")).longValue(),
                raw.stream().map(Number::longValue).toList());
    }

    /** 结果应当**全部**落在假号段内（否则说明查询没被限定住，断言会跟着真实数据漂移） */
    private List<Long> seq(Page page) {
        assertTrue(page.ids().stream().allMatch(id -> id >= FAKE_SPU_ID_BASE),
                "检索结果混进了真实语料（查询没限定在假号段内）：" + page.ids());
        return page.ids().stream().map(id -> id - FAKE_SPU_ID_BASE).toList();
    }

    private record Page(long total, List<Long> ids) {
    }
}
