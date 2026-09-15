package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.dto.SpuSnapshotVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>商品域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而非"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：原先三个方法各写一遍匿名子类
 *       （{@code new ParameterizedTypeReference<ApiResponse<List<SpuSnapshotVO>>>(){}}），
 *       泛型方法里的 {@code T} 一旦被擦除，Jackson 只会把 {@code data} 反序列化成 {@code LinkedHashMap}，
 *       调用方随后 {@code ClassCastException}，而**编译期完全看不出来**。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失。</li>
 *   <li><b>URL 只写一次</b>：原先 {@code "/product/stat/top-sales?limit=" + limit} 这种拼接散落在客户端类里
 *       （拼接还容易漏 URL 编码），现在路径与动词集中在这里。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上（直连分支由 {@code OutboundRestClientFactory} 显式挂上）的
 *       {@code OutboundHeadersInterceptor} 统一注入，接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link ProductStatClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 → {@code BusinessException(500, "系统繁忙，请稍后重试")}、
 *       业务码非 0 → 原样透传，都在客户端类里（那是"域语义"，不是"HTTP 形状"）。</li>
 *   <li><b>不打日志</b>：日志条数/级别由调用方定（看板要求"每请求恰好一条 WARN"）。</li>
 *   <li><b>不做降级</b>：降级是 service 层的策略（三域各自失败各自降级）。</li>
 *   <li><b>不做形状适配</b>：{@code List<SpuSnapshotVO> → Map<Long, SpuSnapshotVO>}（以及"空集合不发请求"）
 *       留在客户端类里。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类的 {@code unwrap} 里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/v1}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1", contentType = "application/json")
public interface ProductStatApi {

    /** 在架商品数（summary 的 {@code onShelfProductCount}） */
    @GetExchange("/product/stat/enabled-count")
    ApiResponse<Long> enabledCount();

    /** 销量榜：在架商品按销量倒序前 N（limit 由商品域夹取到 [1,20]，这里原样透传） */
    @GetExchange("/product/stat/top-sales")
    ApiResponse<List<SpuSnapshotVO>> topBySales(@RequestParam("limit") int limit);

    /**
     * 批量 SPU 快照（销售额榜补 {@code title/mainImage}）。
     *
     * <p>请求体形状与单体 {@code ProductClient.spus} 逐字一致（key {@code ids}），
     * "空集合不发请求"的判断在客户端类里做。
     */
    @PostExchange("/product/spu/batch")
    ApiResponse<List<SpuSnapshotVO>> spus(@RequestBody Map<String, Object> body);
}
