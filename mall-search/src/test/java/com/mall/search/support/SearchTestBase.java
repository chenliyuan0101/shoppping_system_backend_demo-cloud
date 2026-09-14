package com.mall.search.support;

import com.mall.search.client.ProductIndexDocClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * mall-search 用例基类：<b>默认 hermetic</b> —— 继承本类的套件**不会**碰到 mall-product。
 *
 * <h2>这个类为什么存在（一次真实事故的收尾）</h2>
 * 第一版的检索用例把出站地址指向**活着的 8102**，于是同一份源码的绿/红取决于"外面是否正好跑着 product、
 * 它的 jar/令牌是否正好对得上"：主 agent 的受控实验里，停掉 8102 后同两套用例立刻从绿变红，而且红得很难看
 * —— 没有跳过、没有清晰原因，`Connection refused` 被伪装成 `expected 0 but was 500`，看报告的人会以为 reindex 逻辑坏了。
 * 后来还发生过反向的假绿：外面起着服务但端点/令牌不匹配，3 个用例打到活服务上而失败。
 * ⇒ 结论：**默认必须 hermetic**；要碰网络必须**显式声明**。
 *
 * <h2>怎么做到"默认 hermetic"</h2>
 * 本类用 {@link MockitoBean} 把出站客户端 {@link ProductIndexDocClient} 换成桩：
 * 继承本类 ⇒ 出站调用被 MOCK 拦住（未打桩的调用返回空/抛错，**不可能**真的发 HTTP）。
 * 需要真调 product 的用例**不要继承本类**，而是继承 {@link SearchTestSupport} 并自己声明
 * （见 {@code com.mall.search.integration.SearchReindexIntegrationTest}：它显式退出 hermetic + 用
 * {@code assumeTrue} 守卫 + 把跳过原因写进报告）。
 *
 * <h2>零覆盖配置</h2>
 * 本类**不写任何 profile 覆盖**（尤其不覆盖 {@code mall.internal.token}）：
 * <ul>
 *   <li>写进 {@code src/test/resources/application.properties} 会被 dev profile 静默盖掉（P1~P5 的坑）；</li>
 *   <li>写"测试专用假令牌"放进 inlined properties 虽然优先级够，但会让**出站调用**带上对方不认的钥匙
 *       ⇒ 403 ⇒ 被映射成 500，表现成"reindex 失败"，且换个环境就漂移（正是本轮返工的根因）。</li>
 * </ul>
 * 现在鉴权相关的值一律**取自运行时配置**（{@link SearchTestSupport#internalToken} 用 {@code @Value} 注入）：
 * "带上正确令牌"永远等于"带上这个环境自己配的那把"，**没有测试专用开关/专用令牌**。
 *
 * <h2>三层测试（哪层验哪层）</h2>
 * <ul>
 *   <li><b>ES 单层</b>（{@code InternalSearchApiEsTest}）：只依赖真 ES。鉴权闸门双向、自检快照、检索筛选/排序/分页、
 *       **mapping 12 字段逐字 + title=smartcn + subtitle 无 analyzer + mainImage keyword(index:false) + 1 分片 0 副本 + 无别名**；</li>
 *   <li><b>内容打桩层</b>（{@code ProductIndexContentStubbedEsTest}）：沿用本基类的桩，验"取到内容→写 ES 12 字段 /
 *       内容为空→删除 / 内容源抛错→返回 false 且**不删文档** / 按品牌一次取齐（无 N+1）"；</li>
 *   <li><b>显式集成层</b>（{@code SearchReindexIntegrationTest}）：真调 product，验跨服务那一段（reindex 一致性/幂等、
 *       单条同步、按品牌同步）；product 不在时**跳过并写清原因**，报告里单列。</li>
 * </ul>
 *
 * <h2>Skipped 的口径</h2>
 * 继承本类的套件**不允许出现 Skipped**（ES 不可达时直接红，见 {@link #assertEsReachable()}）；
 * 只有显式集成层可能跳过，且必须单独汇报。
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class SearchTestBase extends SearchTestSupport {

    /**
     * 出站内容源的**桩**（本基类的核心）。
     *
     * <p>protected：子类（打桩层）需要它来 {@code when(...)} 安排返回值。
     * 未打桩的调用由 Mockito 默认行为处理（返回 null/空集合）——**不会**真的发 HTTP。
     */
    @MockitoBean
    protected ProductIndexDocClient productIndexDocClient;

    /**
     * ES 必须可达 —— 继承本类的套件打的是**真 ES**，ES 不在就应当**红**，不许静默跳过。
     *
     * <p>⚠️ 这条守卫**只在本类**（"正常环境"基类），**故意把 ES 指向坏端口**的降级/失败语义套件
     * （{@code com.mall.search.degraded.*}）直接继承 {@link SearchTestSupport}，因此不受本守卫约束
     * —— 否则"ES 挂了会怎样"这种用例永远跑不起来。
     *
     * <p><b>为什么要等一小会儿（有界重试）</b>：{@code mall_product} 是**共享索引**，
     * 全量重建的语义是"删旧索引 → 建新索引 → 写回"，在这几秒里索引**真的不存在**。
     * 我实测撞上过：05:45 有人（活着的服务/主 agent 的脚本）正在 reindex，我的用例当场看到
     * {@code no_shard_available_action_exception} / 索引不存在，于是报出一堆"筛选用例失败"，
     * 而实现根本没问题 —— 那是**结构性假红**。这里给一个 10s 的有界等待窗口，
     * ES 真挂了照样在窗口结束后红（不是放宽：等不到就失败，只是不在重建窗口里误判）。
     */
    @BeforeEach
    protected void assertEsReachable() {
        long deadline = System.currentTimeMillis() + 10_000L;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                boolean exists = elasticsearchClient.indices().exists(e -> e.index(INDEX)).value();
                if (exists) {
                    return;
                }
                last = new IllegalStateException("索引 " + INDEX + " 不存在（可能正被别人全量重建）");
            } catch (Exception e) {
                last = e;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Elasticsearch 不可达或索引 " + INDEX + " 在 10s 内始终不存在"
                + "（先确认 ES 在跑：curl -s -k -u elastic:elasticsearch123456 "
                + "https://127.0.0.1:9200/_cluster/health；若恰好有全量重建在进行，重跑即可）", last);
    }
}
