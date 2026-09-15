package com.mall.content.client;

import com.mall.content.support.ApiResponse;
import com.mall.content.support.dto.HomeFeedVO;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * <b>商品域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：原先调用点必须写
 *       {@code new ParameterizedTypeReference<ApiResponse<HomeFeedVO>>() {}}——泛型方法里的 {@code T}
 *       会被擦除，Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，调用方随后
 *       {@code ClassCastException}，而且**编译期完全看不出来**。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒
 *       这个坑结构性消失。</li>
 *   <li><b>URL 只写一次</b>：路径与 HTTP 动词集中在这里，客户端类里不再出现
 *       {@code uri(uri -> uri.path(...).queryParam(...))} 这样的拼装。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上的 {@code OutboundHeadersInterceptor} 统一注入
 *       （直连分支由 {@code OutboundRestClientFactory} 显式挂同一个拦截器），
 *       接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link ProductFeedClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 / 空响应 / 业务码非 0 → {@code ProductFeedUnavailableException}
 *       的映射在客户端类里（那是"域语义"，不是"HTTP 形状"）；</li>
 *   <li><b>不打日志</b>：日志条数/级别由调用方定；</li>
 *   <li><b>不做降级</b>：降级是 service 层的策略（{@code HomeServiceImpl} 的 fail-open）。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1/product", contentType = "application/json")
public interface ProductFeedApi {

    /**
     * 首页商品区块（类目树 + 热门 + 新品）。
     *
     * @param size 每个区块条数（商品域会夹取上限，见 {@code ProductQueryServiceImpl}）
     */
    @GetExchange("/home-feed")
    ApiResponse<HomeFeedVO> homeFeed(@RequestParam("size") int size);
}
