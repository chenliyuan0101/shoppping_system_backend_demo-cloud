package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.MemberOrderBriefVO;
import com.mall.admin.support.dto.OrderSummaryVO;
import com.mall.admin.support.dto.OrderTrendPointVO;
import com.mall.admin.support.dto.SpuAmountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>交易域</b>（今天的物理落点是单体 {@code mall-legacy}）出站客户端：看板的订单口径 + 会员订单补数。
 *
 * <h2>它消费的是"已经存在的"内部契约——本批**没有**为看板新造端点</h2>
 * 单体侧 {@code internal/InternalTradeController} 早就把订单口径暴露成
 * {@code /internal/v1/stat/{summary,trend,top-amount,member-order-brief}}（P0/P4 的装配），
 * 因此看板"数据从哪来"这个问题的答案是：<b>用现成的内部契约，一个字段都不改</b>
 * （唯一新增的是给会员列表用的批量补数 {@code /stat/member-order-brief/batch}，见 {@link #memberOrderBriefs}）。
 * ⚠️ <b>为什么不能查单体或订单库</b>：订单表属交易域，BFF 只允许读契约（方案 §2.8 / §1.3）。
 *
 * <h2>路径前缀</h2>
 * {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到），服务间通过服务发现直连。
 * 出站必须带 {@code X-Internal-Token}（两侧 {@code InternalApiAuthInterceptor} 的约定）。
 *
 * <h2>错误语义（与单体 {@code ProductClient}/{@code UserCenterClient} 逐字同口径）</h2>
 * <ul>
 *   <li>传输异常 / 空响应 → {@link BusinessException}(500, {@value #DOWNSTREAM_ERROR_MESSAGE})；</li>
 *   <li>下游业务码非 0 → **原样透传** {@code BusinessException(code, message)}；</li>
 *   <li><b>本类不打日志</b>（只 {@code debug}）："怎么记"由调用方决定——
 *       看板要求"每请求恰好一条 {@code log.warn}"，会员侧要 ERROR。
 *       在这里打就等于把日志条数交给"有几个依赖挂了"去决定，级别也定不下来。</li>
 * </ul>
 */
@Slf4j
@Component
public class TradeStatClient {

    private static final String BASE = "/internal/v1";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public TradeStatClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                           @Value("${mall.trade.base-url:lb://mall-trade}") String baseUrl,
                           @Value("${mall.internal.token:}") String internalToken,
                           @Value("${mall.trade.connect-timeout-ms:300}") long connectTimeoutMs,
                           @Value("${mall.trade.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        // 启动日志：活体核对"交易域到底指向哪"（排查"看板为什么全是 0"的第一行）
        log.info("交易域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** 看板概览的交易口径（今日下单/销售额/待发货/待处理退款） */
    public OrderSummaryVO summary() {
        return get("/stat/summary", new ParameterizedTypeReference<ApiResponse<OrderSummaryVO>>() {
        });
    }

    /** 近 N 天趋势（**连续且补零**由交易域负责；天数钳制也在那边，BFF 原样透传 days） */
    public List<OrderTrendPointVO> trend(int days) {
        return get("/stat/trend?days=" + days, new ParameterizedTypeReference<ApiResponse<List<OrderTrendPointVO>>>() {
        });
    }

    /** 销售额榜（有序，商品可能已删除；条数钳制在交易域，BFF 原样透传 limit） */
    public List<SpuAmountVO> topPaidAmountBySpu(int limit) {
        return get("/stat/top-amount?limit=" + limit, new ParameterizedTypeReference<ApiResponse<List<SpuAmountVO>>>() {
        });
    }

    /** 单个会员的订单口径摘要（后台会员**详情**用，与单体同一条端点） */
    public MemberOrderBriefVO memberOrderBrief(long memberId) {
        return get("/stat/member-order-brief?memberId=" + memberId,
                new ParameterizedTypeReference<ApiResponse<MemberOrderBriefVO>>() {
                });
    }

    /**
     * <b>批量</b>会员订单口径摘要（后台会员**列表**用）：一页**一次**调用（规格 §4 无 N+1 判据）。
     *
     * <p>端点返回 JSON 对象（key 是会员 id 的字符串形式），这里显式转成 {@code Map<Long, …>}——
     * 与单体 {@code ProductClient.minEnabledSkuPrices} 同一手法：把"字符串 key → Long"的转换
     * 收在一个地方，别让它散落到各调用点。
     *
     * @param memberIds 会员 id；空集合 → 空 Map 且**不发起调用**（空批量没有意义，
     *                  而且下游对空集合的返回也无从校验；与 {@code ProductClient}/{@code UserCenterClient} 同口径）
     */
    public Map<Long, MemberOrderBriefVO> memberOrderBriefs(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        Map<String, MemberOrderBriefVO> raw = post("/stat/member-order-brief/batch",
                Map.of("memberIds", List.copyOf(memberIds)),
                new ParameterizedTypeReference<ApiResponse<Map<String, MemberOrderBriefVO>>>() {
                });
        Map<Long, MemberOrderBriefVO> result = new LinkedHashMap<>();
        if (raw == null) {
            return result;
        }
        raw.forEach((key, value) -> {
            try {
                result.put(Long.valueOf(key), value);
            } catch (NumberFormatException e) {
                log.warn("忽略无法解析的会员 id key: {}", key);
            }
        });
        return result;
    }

    // ==================== 内部 ====================

    /**
     * GET 统一入口。
     *
     * <p>⚠️ 具体的 {@code ParameterizedTypeReference} **必须由调用方传入**：泛型方法里的 {@code T} 会被擦除，
     * Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，调用方随后 {@code ClassCastException}
     * ——这个 bug 编译期完全看不出来（P3-4 在单体 {@code UserCenterClient} 上实测踩过）。
     */
    private <T> T get(String uri, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.get().uri(BASE + uri)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            // debug 而不是 error：看板预期内的降级不该刷错误栈（级别由调用方定，见类注释）
            log.debug("调用交易域失败: uri={} err={}", uri, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(uri, response);
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.post().uri(BASE + path)
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.debug("调用交易域失败: path={} err={}", path, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(path, response);
    }

    /** 空响应按传输失败处理（宁可 500，也不要把 null 当"没有数据"）；非 0 业务码原样透传 */
    private <T> T unwrap(String uri, ApiResponse<T> response) {
        if (response == null) {
            log.debug("调用交易域返回空响应: uri={}", uri);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
