package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.dto.MemberOrderBriefVO;
import com.mall.admin.support.dto.OrderSummaryVO;
import com.mall.admin.support.dto.OrderTrendPointVO;
import com.mall.admin.support.dto.SpuAmountVO;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.List;
import java.util.Map;

/**
 * <b>交易域内部契约的声明式接口</b>（Spring HTTP Interface，替换原先手写的 {@code RestClient} 调用）。
 *
 * <h2>为什么这层能有收益（而不是"为了新写法而新写法"）</h2>
 * <ol>
 *   <li><b>泛型不再靠人工传 {@code ParameterizedTypeReference}</b>：这是原先最容易踩的坑——
 *       泛型方法里的 {@code T} 会被擦除，Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，
 *       调用方随后 {@code ClassCastException}，而且**编译期完全看不出来**（P3-4 在单体 {@code UserCenterClient} 上实测踩过）。
 *       声明式接口的返回类型是**方法签名的一部分**，框架从 {@code MethodParameter} 解析泛型 ⇒ 这个坑结构性消失。</li>
 *   <li><b>URL 只写一次</b>：路径与 HTTP 动词集中在这里，客户端类里不再散落 {@code "/stat/trend?days=" + days}
 *       这种字符串拼接（拼接还容易漏 URL 编码）。</li>
 *   <li><b>出站头不再手工写</b>：{@code X-Internal-Token} / {@code X-Trace-Id} 由挂在
 *       {@code @LoadBalanced RestClient.Builder} 上的 {@code OutboundHeadersInterceptor} 统一注入，
 *       接口方法上不需要（也不应该）再写 {@code .header(...)}。</li>
 * </ol>
 *
 * <h2>这一层**不做**什么（刻意留给 {@link TradeStatClient}）</h2>
 * <ul>
 *   <li><b>不处理错误语义</b>：传输异常 → {@code BusinessException(500, "系统繁忙，请稍后重试")}、
 *       业务码非 0 → 原样透传，都在客户端类里（那是"域语义"，不是"HTTP 形状"）。</li>
 *   <li><b>不打日志</b>：日志条数/级别由调用方定（看板要求"每请求恰好一条 WARN"）。</li>
 *   <li><b>不做降级</b>：降级是 service 层的策略（三域各自失败各自降级）。</li>
 * </ul>
 *
 * <p>⚠️ 返回类型是**下游的原始响应体** {@code ApiResponse<T>}（不是 {@code T}）：解包与文案映射统一在
 * 客户端类的 {@code unwrap} 里做，接口只负责"HTTP ↔ 类型"这一步。
 */
@HttpExchange(url = "/internal/v1", contentType = "application/json")
public interface TradeStatApi {

    /** 看板概览的交易口径（今日下单/销售额/待发货/待处理退款） */
    @GetExchange("/stat/summary")
    ApiResponse<OrderSummaryVO> summary();

    /** 近 N 天趋势（连续补零与天数钳制都在交易域，这里原样透传 days） */
    @GetExchange("/stat/trend")
    ApiResponse<List<OrderTrendPointVO>> trend(@RequestParam("days") int days);

    /** 销售额榜（有序；条数钳制在交易域，这里原样透传 limit） */
    @GetExchange("/stat/top-amount")
    ApiResponse<List<SpuAmountVO>> topPaidAmountBySpu(@RequestParam("limit") int limit);

    /** 单个会员的订单口径摘要（后台会员**详情**用） */
    @GetExchange("/stat/member-order-brief")
    ApiResponse<MemberOrderBriefVO> memberOrderBrief(@RequestParam("memberId") long memberId);

    /**
     * <b>批量</b>会员订单口径摘要（后台会员**列表**用，一页一次调用）。
     *
     * <p>下游返回的是 JSON 对象（key 是会员 id 的**字符串**形式）⇒ 这里就声明成
     * {@code Map<String, …>}，"字符串 key → Long" 的转换留在客户端类里做一次。
     */
    @PostExchange("/stat/member-order-brief/batch")
    ApiResponse<Map<String, MemberOrderBriefVO>> memberOrderBriefs(@RequestBody Map<String, Object> body);
}
