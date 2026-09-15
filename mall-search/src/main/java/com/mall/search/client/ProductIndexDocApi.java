package com.mall.search.client;

import com.mall.search.dto.IndexDocsResult;
import com.mall.search.support.ApiResponse;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.Map;

/**
 * <b>商品域"索引文档"内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：原先那处
 *       {@code new ParameterizedTypeReference<ApiResponse<IndexDocsResult>>(){}} 是**必须手写**的——
 *       泛型方法里的 {@code T} 一旦被擦除，Jackson 只会把 {@code data} 反序列化成 {@code LinkedHashMap}，
 *       调用方随后 {@code ClassCastException}（P3-4 在单体上实测踩过）。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失。</li>
 *   <li><b>URL 只写一次</b>：路径与 HTTP 动词集中在这里，客户端类里不再出现
 *       {@code BASE + "/index-docs"} 这种字符串拼接。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上（直连分支由 {@code OutboundRestClientFactory} 显式挂上）的
 *       {@code OutboundHeadersInterceptor} 统一注入，接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link ProductIndexDocClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 / 空响应 → {@code BusinessException(500, "系统繁忙，请稍后重试")}、
 *       业务码非 0 → 原样透传，都在客户端类里（那是"域语义"，不是"HTTP 形状"）；</li>
 *   <li><b>不做形状适配</b>："空集合不发请求""{@code data} 为 null 按空文档集处理"留在客户端类里；</li>
 *   <li><b>不做兜底</b>：拉不到内容就明确失败（规格 §6），绝不在这里用空文档顶替。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/v1/product}：网关有过滤器直接 404 {@code /internal/**}（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1/product", contentType = "application/json")
public interface ProductIndexDocApi {

    /**
     * 取索引文档（三种取法共用同一形状，见 {@link IndexDocsResult}）：
     * <ul>
     *   <li>{@code {"spuIds":[...]}} —— 只返回**在架且未删除**的 spu 的文档；</li>
     *   <li>{@code {"brandId":N}} —— 该品牌下在架商品的文档；</li>
     *   <li>{@code {"pageNum":N,"pageSize":M}} —— 按 spuId 升序的一页（此时 {@code totalInShelf} 才有意义）。</li>
     * </ul>
     * 请求体三种形状的字段名由调用方组装（空集合"不发请求"的判断也在那里）。
     */
    @PostExchange("/index-docs")
    ApiResponse<IndexDocsResult> indexDocs(@RequestBody Map<String, Object> body);
}
