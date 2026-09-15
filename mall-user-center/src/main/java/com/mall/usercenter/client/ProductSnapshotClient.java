package com.mall.usercenter.client;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.dto.SkuSnapshotVO;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 商品域出站客户端（P3-3）：购物车/收藏/足迹展示所需的 SKU/SPU 快照。
 *
 * <p>搬迁前这些数据由**同进程**的 {@code pms.service.ProductQueryService} 提供；
 * 购物车搬进 mall-user-center 之后，商品表不再可达，只能走商品域的内部接口
 * （与 mall-content 的 {@code ProductFeedClient} 同一套样板）：
 * <ol>
 *   <li>地址走服务发现（{@code lb://mall-legacy}；P6 商品域独立后改成 {@code lb://mall-product}，
 *       本类只改一行配置）；</li>
 *   <li>带内部共享密钥 {@code X-Internal-Token}（网关不路由 {@code /internal/**}，§4.10）；</li>
 *   <li><b>硬超时</b>（默认 connect 300ms / read 2000ms）：这是**用户可感知路径**（加购、购物车列表），
 *       不能像首页那样等 2.5s 再降级；</li>
 *   <li><b>只调批量端点</b>：单条查询也走批量接口（一种请求形状、一个超时点），
 *       避免"列表页逐条远程调用"（§2.5 原则三）。</li>
 * </ol>
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link ProductSnapshotApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（应急回退/本地排障）——**直连分支由 {@link OutboundRestClientFactory}
 *       显式挂上同一个拦截器**，因此直连也带内部令牌头（线格式契约测试走的正是这条分支）；超时仍按
 *       connect 300ms / read 2000ms 设置；</li>
 *   <li><b>错误语义</b>：传输异常 / 空响应 / 业务码非 0 → {@link ProductUnavailableException}
 *       （下文两条路径的不对称降级留在调用侧/本类，接口只负责"HTTP ↔ 类型"）；</li>
 *   <li><b>形状适配</b>：入参去重、空集合不发请求；最低价端点的"字符串 key → Long"由接口声明的
 *       {@code Map<Long, Long>} 直接承担。</li>
 * </ul>
 *
 * <p><b>用到的内部端点（均已存在于商品域，P3-3 核对过源码）</b>：
 * <ul>
 *   <li>{@code POST /internal/v1/product/sku/batch}、{@code POST /internal/v1/product/spu/batch}：
 *       购物车列表/加购/改数量所需的"现价、库存、上下架、规格"；</li>
 *   <li>{@code POST /internal/v1/product/sku/min-price/batch}：收藏/足迹列表的"起售价"。
 *       请求体同样是 {@code {"ids":[...]}}，但传的是 <b>SPU</b> id（不是 skuId）；
 *       响应 {@code Map<Long,Long>}（JSON 对象的 key 是字符串形式的 spuId）；
 *       口径：只看 {@code status=1} 的 SKU、忽略价格为空的，没有可用 SKU 的 SPU 不出现在结果里。</li>
 * </ul>
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class ProductSnapshotClient {

    private final ProductSnapshotApi api;

    /**
     * Spring 装配用的构造器（唯一被容器使用的那个，见下面的兼容构造器）。
     */
    @Autowired
    public ProductSnapshotClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                 OutboundRestClientFactory restClients,
                                 @Value("${mall.product.base-url:lb://mall-product}") String baseUrl,
                                 @Value("${mall.product.connect-timeout-ms:300}") long connectTimeoutMs,
                                 @Value("${mall.product.read-timeout-ms:2000}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(ProductSnapshotApi.class);
        // 启动日志：活体核对"商品域到底指向哪"（排查"购物车为什么说商品不存在"的第一行）
        log.info("商品域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * <b>兼容构造器</b>：手工直连（{@code http://host:port}）+ 显式内部令牌，供不启动 Spring 的
     * 线格式契约测试使用（{@code ProductSnapshotClientTest} 就是 {@code new ProductSnapshotClient(
     * RestClient.builder(), url, "test-internal-token", 500, 2000)}）。
     *
     * <p>它**不自己拼装配逻辑**，而是现造一个 {@link OutboundRestClientFactory} 再委托给上面的构造器
     * ⇒ 直连分支与生产分支走的是同一段代码（令牌头由 {@code OutboundHeadersInterceptor} 统一注入），
     * 这正是"直连也要带 {@code X-Internal-Token}"的保证，也是这个重载存在的唯一理由。
     *
     * <p>生产装配不使用本构造器：容器用的入口是那个注入了 {@code OutboundRestClientFactory} 的构造器
     * （因此多构造器下必须显式标 {@code @Autowired}）。
     */
    public ProductSnapshotClient(RestClient.Builder builder, String baseUrl, String internalToken,
                                 long connectTimeoutMs, long readTimeoutMs) {
        this(builder, new OutboundRestClientFactory(internalToken), baseUrl,
                connectTimeoutMs, readTimeoutMs);
    }

    /** 单个 SKU 快照；商品域明确回答"没有"时返回 {@code null}（≠ 下游不可用，见类注释） */
    public SkuSnapshotVO sku(Long skuId) {
        if (skuId == null) {
            return null;
        }
        return skus(List.of(skuId)).stream().findFirst().orElse(null);
    }

    /** 批量 SKU 快照；不存在的 id 不会出现在结果里 */
    public List<SkuSnapshotVO> skus(Collection<Long> skuIds) {
        List<Long> ids = distinct(skuIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return exchange(() -> api.skus(Map.of("ids", ids)), "sku/batch");
    }

    /** 单个 SPU 快照；商品域明确回答"没有"时返回 {@code null} */
    public SpuSnapshotVO spu(Long spuId) {
        if (spuId == null) {
            return null;
        }
        return spus(List.of(spuId)).stream().findFirst().orElse(null);
    }

    /** 批量 SPU 快照；不存在的 id 不会出现在结果里 */
    public List<SpuSnapshotVO> spus(Collection<Long> spuIds) {
        List<Long> ids = distinct(spuIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return exchange(() -> api.spus(Map.of("ids", ids)), "spu/batch");
    }

    /**
     * 批量取"每个 SPU 下启用 SKU 的最低价"（收藏/足迹列表的起售价）。
     *
     * <p>契约与商品域逐字一致：入参是 <b>SPU</b> id 集合；返回 Map 的 key 是 spuId；
     * 口径为"只看 {@code status=1} 的 SKU、忽略价格为空的；没有可用 SKU 的 SPU 不会出现在结果里"
     * ——调用方按 0 处理（与改造前同进程契约完全一致）。
     *
     * <p><b>失败降级（刻意不抛异常）</b>：起售价是展示字段，不该因为它让"我的收藏"整页报错；
     * 下游不可用/超时/业务码非 0 时记 WARN 并按空 Map 处理，对外表现即"起售价 0"。
     * 注意这与 {@link #skus}/{@link #spus} 的不对称是刻意的：那两条是加购与购物车列表的**校验依据**
     * （"商品不存在或已下架"必须真的报错），因此照常抛 {@link ProductUnavailableException}。
     */
    public Map<Long, Long> minEnabledSkuPrices(Collection<Long> spuIds) {
        List<Long> ids = distinct(spuIds);
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, Long> prices = exchange(() -> api.minEnabledSkuPrices(Map.of("ids", ids)),
                    "sku/min-price/batch");
            return prices == null ? Map.of() : prices;
        } catch (ProductUnavailableException e) {
            log.warn("商品域最低价接口不可用，收藏/足迹起售价按 0 降级: spuCount={} err={}",
                    ids.size(), e.getMessage());
            return Map.of();
        }
    }

    // ---------- private ----------

    /**
     * 把"调用接口"这一步收敛成商品域的失败。
     *
     * <p>为什么还要这层 try/catch：声明式接口解决的是"HTTP ↔ 类型"的样板，**不解决错误语义**——
     * 超时、连接被拒、5xx、反序列化失败都要收敛到"商品域不可用"，由调用方/全局处理器决定表现。
     */
    private <T> T exchange(Supplier<ApiResponse<T>> invocation, String action) {
        ApiResponse<T> response;
        try {
            response = invocation.get();
        } catch (Exception e) {
            // 超时、连接被拒、5xx、反序列化失败都收敛到"商品域不可用"，由调用方/全局处理器决定表现
            throw new ProductUnavailableException("调用商品域失败: " + action + " (" + e + ")", e);
        }
        if (response == null) {
            throw new ProductUnavailableException("商品域返回空响应: " + action);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new ProductUnavailableException(
                    "商品域返回业务错误: " + action + ", code=" + response.getCode());
        }
        return response.getData();
    }

    private static List<Long> distinct(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
