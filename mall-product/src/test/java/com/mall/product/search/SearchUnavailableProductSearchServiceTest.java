package com.mall.product.search;

import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductSearchService;
import com.mall.product.service.impl.RemoteProductSearchService;
import com.mall.product.service.impl.SearchUnavailableProductSearchService;
import com.mall.product.support.CacheKeys;
import com.mall.common.support.CacheService;
import com.mall.product.support.ProductTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * <b>接线守卫 + 降级语义</b>测试（照 P5 的 {@code MarketingRemoteWiringTest} 的思路）。
 *
 * <h2>P6-3 的改动（这份用例就是被它"逼回来更新"的那个）</h2>
 * P6-1 期本类的守卫断言是"上下文里的 {@link ProductSearchService} 就是降级实现"，
 * 并**写明**"换实现时它会立刻变红，逼替换者回来把'降级'改成真正的'远程 + 降级'"。
 * P6-3 正是那个替换 ⇒ 现在守卫断言翻转成：
 * <ol>
 *   <li>注入到的是 **{@link RemoteProductSearchService}**（唯一实现路径）；</li>
 *   <li>{@link SearchUnavailableProductSearchService} **不再是 bean**（它退化为远程实现内部的降级体）
 *       —— 这条断言把"两个实现谁能赢"的模糊态从**结构上**排除掉；</li>
 *   <li>而**降级语义本身一条没放宽**：本类直接 {@code new} 出降级体，逐方法钉死那 8 条字面值语义
 *       （{@code -1} / {@code "standard"} / {@code null} / {@code false} / {@code 0} / 抛"索引不可用"）。</li>
 * </ol>
 *
 * <h2>为什么还要"直接 new 出来验"</h2>
 * 因为降级体虽然不再是 bean，却仍然是**真实运行时**被远程实现调用的代码
 * （三种失败都委托给它）⇒ 它的语义必须继续被钉住，否则"索引不可用被伪装成没有数据"会从后门溜回来。
 *
 * <h2>关于断言里的字面值</h2>
 * {@code -1} / {@code "standard"} / {@code null} / {@code false} / {@code 0} 都是
 * **单体 {@code ProductSearchServiceImpl} 在 ES 不可用时的现状取值**（照抄，不是自创）；
 * 断言写死字面值就是为了让"改动降级语义"这件事必须过一次红灯。
 */
class SearchUnavailableProductSearchServiceTest extends ProductTestBase {

    /** 9 开头：真实数据里不存在（不影响单体/search 正在消费的 mall:es:pending） */
    private static final long FAKE_SPU_ID = 9_100_000_003L;

    @Autowired
    private ProductSearchService productSearchService;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private SpuMapper spuMapper;

    @Autowired
    private ApplicationContext applicationContext;

    /** 降级体（远程实现内部持有的那一个）：直接构造，专验语义 */
    private SearchUnavailableProductSearchService degraded;

    @BeforeEach
    void buildDegradedBody() {
        degraded = new SearchUnavailableProductSearchService(cacheService, spuMapper);
    }

    // ==================================================================
    // ① 接线守卫（P6-3：翻转成"是远程实现 + 降级体不再是 bean"）
    // ==================================================================

    @Test
    @DisplayName("[接线守卫] 上下文里的 ProductSearchService 是**远程实现**；降级体**不是 bean**（无候选歧义）")
    void productSearchService_isTheRemoteImplementation() {
        assertThat(productSearchService)
                .as("P6-3 之后检索必须走远程（mall-search）；若这里变回降级实现，"
                        + "说明有人把 @Primary/@Service 改坏了 ⇒ 前台关键字检索会静默走 MySQL，"
                        + "而且 P6-2 的检索能力白搬了")
                .isInstanceOf(RemoteProductSearchService.class);

        // 比 isInstanceOf 更严：**脱掉 AOP 代理之后**拿到的是恰好这一类。
        // ⚠️ 必须脱代理：support/MethodLogAspect 的切点是 execution(* com.mall.product..service..*(..))，
        //    注入点拿到的是 CGLIB 子类（...$$SpringCGLIB$$0），直接比 getClass() 会永远为假。
        Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(productSearchService);
        assertEquals(RemoteProductSearchService.class, target.getClass(),
                "注入到的实现类（脱代理后）必须恰好是远程实现本身");

        // 唯一装配路径：降级体不再是 bean ⇒ ProductSearchService 类型只有 1 个候选，
        // 不会出现"两个实现谁能赢"（这与 P6-3 规格 §2.1 的要求一致）
        assertThat(applicationContext.getBeansOfType(ProductSearchService.class))
                .as("ProductSearchService 的 bean 必须只有 1 个（唯一装配路径）")
                .hasSize(1);
        assertThat(applicationContext.getBeansOfType(SearchUnavailableProductSearchService.class))
                .as("降级体必须**不是** bean（它由远程实现内部持有）；否则注入点会出现候选歧义")
                .isEmpty();

        // 索引名不稳定也会出错（P6-2 保持 mall_product 不变，不引入别名）
        assertEquals("mall_product", ProductSearchService.INDEX);
    }

    // ==================================================================
    // ② 降级语义：逐方法（字面值 = 单体 ES 不可用时的现状）
    // ==================================================================

    @Test
    @DisplayName("[降级语义] count()=-1、pendingCount()=-1（不可用是 -1，不是 0）")
    void unavailableCounts() {
        assertEquals(-1L, degraded.count(),
                "count() 必须是 -1（照抄单体：索引不存在/读取失败都是 -1）；0 的含义是『索引存在但没有文档』");
        assertEquals(-1L, degraded.pendingCount(), "pendingCount() 必须是 -1（索引链路不可用）");
    }

    @Test
    @DisplayName("[降级语义] titleAnalyzer() 照抄单体探测链的兜底值 standard（并记住它≠分词器可用）")
    void titleAnalyzerIsTheMonolithFallbackValue() {
        assertEquals("standard", degraded.titleAnalyzer(),
                "必须与单体『探测链全失败后的兜底值』逐字一致（照抄，不自创）");
    }

    @Test
    @DisplayName("[降级语义] search 抛内部信号（不是返回空结果）——否则前台关键字搜索会静默变空")
    void searchSignalsUnavailableInsteadOfReturningEmpty() {
        assertThatThrownBy(() -> degraded.search("耳机", null, null, null, null, "default", 1, 8))
                .as("只有抛异常才会触发 ProductPortalServiceImpl 的 MySQL 回落分支")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引不可用");
    }

    @Test
    @DisplayName("[降级语义] 读索引类方法：findById→null、searchByTitle→空列表（照抄 catch 分支）")
    void unavailableReads() {
        assertThat(degraded.findById(1_001L)).isNull();
        assertThat(degraded.searchByTitle("耳机", 5)).isEmpty();
    }

    @Test
    @DisplayName("[降级语义] 写索引类方法：safe 空实现，**绝不抛异常**（业务事务里被调用）")
    void unavailableWritesNeverThrow() {
        // 先只断言"不抛"——把返回值的断言放在外面，失败信息才指向具体方法而不是一句 doesNotThrow
        assertThatCode(() -> {
            degraded.reindex();
            degraded.syncProduct(1_001L);
            degraded.deleteProduct(1_001L);
            degraded.syncByBrand(1L);
            degraded.drainPending(200);
            degraded.markDirty(null);
            degraded.markDirty(List.of());
            degraded.markDirty(Arrays.asList(null, null));
            degraded.syncLater(FAKE_SPU_ID);
            degraded.syncBrandLater(9_999_999_999L);
        }).as("除 search 外的方法都在业务链路上被调用，抛异常等于『索引不可用导致下单失败』")
                .doesNotThrowAnyException();

        // 再逐个断言返回值语义（字面值 = 单体 ES 不可用时的现状）
        assertThat(degraded.reindex()).as("无索引可重建 ⇒ null（不是『重建了 0 篇』）").isNull();
        assertThat(degraded.syncProduct(1_001L)).as("照抄单体失败路径 ⇒ false").isFalse();
        assertThat(degraded.syncByBrand(1L)).as("索引不可用 ⇒ 同步成功 0 篇").isZero();
        assertThat(degraded.drainPending(200)).as("刻意不消费兜底队列（见类注释 ⑤）").isEmpty();
    }

    @Test
    @DisplayName("[降级语义] markDirty 仍写 Redis 兜底队列（spec §4.5 的『待同步标记』），键名与单体逐字一致")
    void markDirtyStillMarksTheFallbackQueue() {
        assumeTrue(redisUp(), "本机没有 Redis：标记是 fail-open 的（只告警），按跳过处理");
        trackPendingSync(FAKE_SPU_ID);
        redisTemplate.opsForSet().remove(CacheKeys.esPendingSync(), String.valueOf(FAKE_SPU_ID));

        degraded.markDirty(List.of(FAKE_SPU_ID));

        assertEquals("mall:es:pending", CacheKeys.esPendingSync(),
                "键名必须逐字等于单体的 CacheKeys.esPendingSync()（单体/search 的定时任务消费的就是它）");
        assertThat(redisTemplate.opsForSet().isMember(CacheKeys.esPendingSync(), String.valueOf(FAKE_SPU_ID)))
                .as("标记必须真的落进兜底集合，否则第 5 条库存细节（变更后标记索引待同步）就没有落点")
                .isTrue();
    }

    // ==================================================================
    // ③ 端到端：检索不可用**不影响**前台读
    //    （测试上下文里 mall.search.base-url 指向死端口，见 ProductTestBase 的注释）
    // ==================================================================

    @Test
    @DisplayName("[端到端/降级] 检索不可用时，前台按关键字仍能取到数据（走 MySQL LIKE 回落分支）")
    void keywordSearch_fallsBackToMysql_andStillReturnsData() throws Exception {
        // 用 seed 商品的完整标题做关键字：库里必然命中（LIKE '%标题%'）
        String seedTitle = stringOf("SELECT title FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE id = 1001 AND status = 1");
        assertThat(seedTitle).as("seed SPU 1001 必须存在且上架（否则本用例失去意义）").isNotBlank();

        // ① 先证明"检索能力确实不可用"（否则下面那条断言可能是在验证别的路径）：
        //    远程实现在这个上下文里必然失败（base-url 指死端口）
        assertThatThrownBy(() -> productSearchService.search(seedTitle, null, null, null, null, "default", 1, 10))
                .as("检索不可用时，远程实现必须抛信号（调用方才回落 MySQL）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("索引不可用");

        clearProductPortalCache();   // 货架分页有缓存，必须清掉才能真的打到服务层

        // ② 再证明"前台照样有数据"——若实现被换成"返回空结果"，这里会拿到 total=0 而变红
        MvcResult r = mockMvc.perform(get("/api/product/page").param("keyword", seedTitle))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        int total = ((Number) com.jayway.jsonpath.JsonPath.read(body(r), "$.data.total")).intValue();
        assertThat(total)
                .as("关键字检索必须回落到 MySQL LIKE 并取到 >=1 条；total=0 说明『索引不可用』被伪装成了『没有数据』")
                .isGreaterThanOrEqualTo(1);

        // ③ 第三条独立佐证：返回的那一条真的来自库（比 id，而不是比条数）
        int returnedId = ((Number) com.jayway.jsonpath.JsonPath.read(body(r), "$.data.list[0].spuId")).intValue();
        assertThat(countOf("SELECT COUNT(*) FROM " + EXPECTED_SCHEMA
                + ".pms_spu WHERE id = ? AND status = 1 AND deleted = 0", returnedId))
                .as("回落路径返回的 spuId 必须是库里真实存在的上架商品")
                .isEqualTo(1L);
    }
}
