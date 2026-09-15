package com.mall.marketing.client;

import com.mall.marketing.support.ApiResponse;
import com.mall.marketing.support.dto.MemberBriefVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>会员域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：这是原先最容易踩的坑——
 *       泛型方法里的 {@code T} 会被擦除，Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，
 *       调用方随后 {@code ClassCastException}，而且**编译期完全看不出来**
 *       （P3-4 在单体 {@code UserCenterClient} 上实测踩过，本类注释里也记着）。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒
 *       这个坑结构性消失。</li>
 *   <li><b>URL 只写一次</b>：{@code BASE + path} 的字符串拼接集中到这里，客户端类里不再散落。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上的 {@code OutboundHeadersInterceptor} 统一注入
 *       （直连分支由 {@code OutboundRestClientFactory} 显式挂同一个拦截器），
 *       接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link UserCenterMemberClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 / 空响应 → {@code BusinessException(500, 系统繁忙，请稍后重试)}、
 *       业务码非 0 → **原样透传** {@code BusinessException(code, message)}，都在客户端类里；</li>
 *   <li><b>不打日志</b>：日志由调用方/客户端类决定；</li>
 *   <li><b>不做降级</b>：营销域对这条依赖的口径是"失败即整体失败"，没有降级分支。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类里做，接口只负责"HTTP ↔ 类型"这一步。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@HttpExchange(url = "/internal/v1/user", contentType = "application/json")
public interface UserCenterMemberApi {

    /**
     * <b>批量</b>取会员简要信息（用户名/昵称）。
     *
     * <p>请求体形状 {@code {memberIds:[...]}}、响应 {@code ApiResponse<List<MemberBriefVO>>}
     * 逐字对齐 user-center 的 {@code POST /internal/v1/user/member/batch}。
     * 空集合由客户端类拦下（不发起调用），这里只声明"怎么发"。
     */
    @PostExchange("/member/batch")
    ApiResponse<List<MemberBriefVO>> memberBatch(@RequestBody Map<String, Object> body);
}
