package com.mall.product.client;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.product.dto.ProductIdPage;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.dto.SearchStatusVO;
import com.mall.product.support.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 检索域出站客户端（P6-3）：调 {@code mall-search} 的 7 个内部端点。
 *
 * <h2>为什么是"客户端"而不是把 RestClient 写在 Service 里</h2>
 * 这样测试可以在**这一层**换替身（{@code @MockitoBean SearchProductsClient}），
 * 而{@code RemoteProductSearchService} 的"哪种失败怎么回落"逻辑仍然是被测的真代码。
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link SearchProductsApi} 承担</h2>
 * 本类保留**域语义**三件事，其余（路径/动词/请求头/反序列化泛型）交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练，也由 {@link OutboundRestClientFactory} 补上同一套头），
 *       并按域设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输失败 / 超时 / 空响应 → {@link SearchRemoteException}（带 uri 与原因）；
 *       下游业务码非 0 **原样返回**，由调用方决定回落与日志；</li>
 *   <li><b>数据形状适配</b>：检索请求体按下游契约拼装（{@code categoryIds} 为 null ⇒ 空数组，
 *       而不是 JSON {@code null}），分页/排序字段名逐字对齐 mall-search 的内部端点。</li>
 * </ul>
 *
 * <h2>错误处理口径（与 mall-search 的 {@code ProductIndexDocClient} 同一套，不另立一套）</h2>
 * <ul>
 *   <li>**返回** {@link ApiResponse}（不在这里把非 0 业务码拍成异常）——因为"返回非 0 业务码"
 *       是规格要求的**三种回落场景之一**，调用方需要能把它与"传输失败"区分开来记日志；</li>
 *   <li>传输失败 / 超时 / 空响应 → 抛 {@link SearchRemoteException}（带原因），由调用方决定回落；</li>
 *   <li>**不做本地兜底**：本类里绝不查库（规格 §2.1：不许在远程实现里再写一套 MySQL 查询）。</li>
 * </ul>
 *
 * <h2>超时为什么是 300ms / 2500ms</h2>
 * 照 P2 的**实测口径**：下游冷启动首次调用实测 1.98s，照抄"目标值 300ms"会把正常请求判成故障。
 * 所以连接 300ms、读 2500ms（与 mall-search 调 product 的那套值一致）。
 */
@Slf4j
@Component
public class SearchProductsClient {

    /**
     * 端点前缀。**路径的唯一来源是 {@link SearchProductsApi}**（接口上的 {@code @HttpExchange}），
     * 这里保留常量只为一件事：失败/空响应文案里带上与改造前**逐字相同**的 uri
     * （调用方会把 {@code e.getMessage()} 拼进降级原因与 WARN 日志）。
     */
    private static final String BASE = "/internal/v1/search";

    private final SearchProductsApi api;

    public SearchProductsClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                OutboundRestClientFactory restClients,
                                @Value("${mall.search.base-url:lb://mall-search}") String baseUrl,
                                @Value("${mall.search.connect-timeout-ms:300}") long connectTimeoutMs,
                                @Value("${mall.search.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(SearchProductsApi.class);
        log.info("检索域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** 按条件检索：返回命中的 spuId（已排序）+ 总数 */
    public ApiResponse<ProductIdPage> searchProducts(String keyword, List<Long> categoryIds, Long brandId,
                                                     Long minPrice, Long maxPrice, String sort,
                                                     long pageNum, long pageSize) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("keyword", keyword);
        body.put("categoryIds", categoryIds == null ? List.of() : categoryIds);
        body.put("brandId", brandId);
        body.put("minPrice", minPrice);
        body.put("maxPrice", maxPrice);
        body.put("sort", sort);
        body.put("pageNum", pageNum);
        body.put("pageSize", pageSize);
        return call(BASE + "/products", () -> api.searchProducts(body));
    }

    /** 全量重建索引（运维端点；本服务无调用方，见 {@code RemoteProductSearchService#reindex}） */
    public ApiResponse<com.mall.product.support.dto.ReindexResult> reindex() {
        return call(BASE + "/reindex", () -> api.reindex(Map.of()));
    }

    /** 自检快照：文档数 / 待同步数 / 分词器 */
    public ApiResponse<SearchStatusVO> status() {
        return call(BASE + "/status", api::status);
    }

    /** 单条同步（在架则写、下架则删）；返回 true=已按最新状态落索引 */
    public ApiResponse<Boolean> syncProduct(long spuId) {
        return call(BASE + "/sync/" + spuId, () -> api.syncProduct(spuId, Map.of()));
    }

    /** 从索引删除（幂等） */
    public ApiResponse<Void> deleteProduct(long spuId) {
        try {
            ApiResponse<Void> response = api.deleteProduct(spuId);
            if (response == null) {
                // 空响应按传输失败处理：宁可回落，也不要把 null 当"删掉了"
                throw new SearchRemoteException("检索域返回空响应: uri=" + BASE + "/product/" + spuId);
            }
            return response;
        } catch (SearchRemoteException e) {
            throw e;
        } catch (Exception e) {
            // 文案与改造前逐字一致（这一步没有"业务码判断"，所以单独一条消息更好定位）
            throw new SearchRemoteException("调用检索域删除失败: spuId=" + spuId + " err=" + e.getMessage(), e);
        }
    }

    /** 品牌维度批量重写；返回成功同步条数 */
    public ApiResponse<Integer> syncByBrand(long brandId) {
        return call(BASE + "/sync-by-brand/" + brandId, () -> api.syncByBrand(brandId, Map.of()));
    }

    /**
     * 按 id 取**索引文档**（P6-5 #5 端点；本服务侧唯一的调用方是
     * {@code RemoteProductSearchService#findById}）。
     *
     * <p>三种结果必须分清（这是本方法存在的理由，别把后两种合成一种）：
     * <ul>
     *   <li>{@code code=0, data=<文档>}：索引里有这篇 ⇒ 返回文档；</li>
     *   <li>{@code code=0, data=null}：这是**结论**——"索引里没有它"（商品下架/删除/从未入索引）；</li>
     *   <li>传输失败或 {@code code≠0}（例如 search 侧 ES 不可达时它明确报 500）：这是**读不到** ——
     *       调用方绝不能把它当成"不存在"（方向完全相反的结论）。本方法用异常表达这一类
     *       （与其它出站方法同口径：{@link SearchRemoteException}）。</li>
     * </ul>
     */
    public ApiResponse<ProductSearchDoc> productDoc(long spuId) {
        return call(BASE + "/product/" + spuId, () -> api.productDoc(spuId));
    }

    // ==================== 内部 ====================

    /**
     * 把"调用接口"这一步的**传输异常**收敛成 {@link SearchRemoteException}，并把空响应按失败处理。
     *
     * <p>为什么还要这层 try/catch：声明式接口解决的是"HTTP ↔ 类型"的样板，**不解决错误语义**——
     * 连接被拒/读超时抛的是 {@code ResourceAccessException}，调用方
     * （{@code RemoteProductSearchService}）要靠 {@link SearchRemoteException} 这一个类型
     * 区分"读不到"与"下游说没有"，并据此回落 MySQL。
     *
     * @param uri        仅用于失败文案（路径本身在 {@link SearchProductsApi}）
     * @param invocation 一次接口调用
     */
    private <T> ApiResponse<T> call(String uri, Supplier<ApiResponse<T>> invocation) {
        try {
            ApiResponse<T> response = invocation.get();
            if (response == null) {
                // 空响应按传输失败处理：宁可回落，也不要把 null 当"没有数据"
                throw new SearchRemoteException("检索域返回空响应: uri=" + uri);
            }
            return response;
        } catch (SearchRemoteException e) {
            throw e;
        } catch (Exception e) {
            throw new SearchRemoteException("调用检索域失败: uri=" + uri + " err=" + e.getMessage(), e);
        }
    }

    /**
     * 出站调用的传输层失败（不可达 / 超时 / 空响应）。
     *
     * <p>刻意是 **RuntimeException**：调用链上（{@code ProductPortalServiceImpl#searchByEs}）
     * 那个 catch 拦的是 {@code Exception}，所以它能照常触发 MySQL 回落，且不需要在每层写 throws。
     */
    public static class SearchRemoteException extends RuntimeException {
        public SearchRemoteException(String message) {
            super(message);
        }

        public SearchRemoteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
