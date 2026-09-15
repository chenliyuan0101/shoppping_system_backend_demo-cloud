package com.mall.product.client;

import com.mall.product.dto.ProductIdPage;
import com.mall.product.dto.ProductSearchDoc;
import com.mall.product.dto.SearchStatusVO;
import com.mall.product.support.ApiResponse;
import com.mall.product.support.dto.ReindexResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * <b>检索域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：原先 7 个方法各写一遍
 *       {@code new ParameterizedTypeReference<ApiResponse<X>>() {}}（最长的那个还是嵌套在
 *       {@code post(...)} 调用里）。声明式接口的返回类型是**方法签名的一部分**，框架从
 *       {@code MethodParameter} 解析泛型 ⇒ 这类样板（以及"擦除后变成 LinkedHashMap"的隐患）结构性消失。</li>
 *   <li><b>URL 只写一次</b>：路径与 HTTP 动词集中在这里，客户端类里不再散落
 *       {@code BASE + "/sync/" + spuId} 这种字符串拼接（拼接还容易漏 URL 编码）。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder}（或直连分支上显式加的那个）的
 *       {@code OutboundHeadersInterceptor} 统一注入，接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link SearchProductsClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输失败/超时/空响应 → {@code SearchRemoteException}（带 uri 与原因），
 *       在客户端类里统一收敛——那是"域语义"，不是"HTTP 形状"；</li>
 *   <li><b>不判业务码</b>：{@code code != 0} 要**原样返回**给调用方（规格要求的三种回落场景之一是
 *       "返回了响应但非 0 码"，调用方要能把它与传输失败区分开）⇒ 所以这里**不抛**，
 *       只把下游原始响应体交回去。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：与改造前
 * {@code SearchProductsClient} 的 7 个公开方法签名逐字一致 ⇒ 调用方
 * （{@code RemoteProductSearchService}）与所有 {@code @MockitoBean} 替身一行都不用改。
 */
@HttpExchange(url = "/internal/v1/search", contentType = "application/json")
public interface SearchProductsApi {

    /** 按条件检索：返回命中的 spuId（已排序）+ 总数（条件字段名下划线/驼峰口径由下游定，这里原样转交） */
    @PostExchange("/products")
    ApiResponse<ProductIdPage> searchProducts(@RequestBody Map<String, Object> body);

    /** 全量重建索引（运维端点；本服务无调用方，见 {@code RemoteProductSearchService#reindex}） */
    @PostExchange("/reindex")
    ApiResponse<ReindexResult> reindex(@RequestBody Map<String, Object> body);

    /** 自检快照：文档数 / 待同步数 / 分词器 */
    @GetExchange("/status")
    ApiResponse<SearchStatusVO> status();

    /** 单条同步（在架则写、下架则删）；返回 true=已按最新状态落索引 */
    @PostExchange("/sync/{spuId}")
    ApiResponse<Boolean> syncProduct(@PathVariable("spuId") long spuId, @RequestBody Map<String, Object> body);

    /** 从索引删除（幂等） */
    @DeleteExchange("/product/{spuId}")
    ApiResponse<Void> deleteProduct(@PathVariable("spuId") long spuId);

    /** 品牌维度批量重写；返回成功同步条数 */
    @PostExchange("/sync-by-brand/{brandId}")
    ApiResponse<Integer> syncByBrand(@PathVariable("brandId") long brandId, @RequestBody Map<String, Object> body);

    /**
     * 按 id 取**索引文档**（P6-5 #5 端点）。
     *
     * <p>与 {@code DELETE} 同路径不同动词 —— 桩服务按方法区分，避免"取"被当成"删"。
     */
    @GetExchange("/product/{spuId}")
    ApiResponse<ProductSearchDoc> productDoc(@PathVariable("spuId") long spuId);
}
