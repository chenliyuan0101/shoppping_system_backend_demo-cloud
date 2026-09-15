package com.mall.admin.client;

import com.mall.admin.config.OutboundRestClientFactory;
import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.MemberOrderBriefVO;
import com.mall.admin.support.dto.OrderSummaryVO;
import com.mall.admin.support.dto.OrderTrendPointVO;
import com.mall.admin.support.dto.SpuAmountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>交易域</b>出站客户端：看板的订单口径 + 会员订单补数。
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link TradeStatApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练用），并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输异常/空响应 → {@code BusinessException(500, 系统繁忙，请稍后重试)}；
 *       下游业务码非 0 → **原样透传** {@code BusinessException(code, message)}；</li>
 *   <li><b>数据形状适配</b>：批量端点把 {@code Map<String,…>} 的字符串 key 转成 {@code Map<Long,…>}
 *       （与单体 {@code ProductClient.minEnabledSkuPrices} 同一手法，转换只在一个地方做）。</li>
 * </ul>
 * <b>本类仍不打日志</b>（只 debug）："怎么记"由调用方决定——看板要求"每请求恰好一条 WARN"，
 * 会员侧要 ERROR；在这里打就等于把日志条数交给"有几个依赖挂了"决定。
 *
 * <h2>为什么保留这个类（而不是让调用方直接用接口）</h2>
 * ① 上面那三件事仍需要一个落点；② 调用方（{@code AdminDashboardServiceImpl}/{@code AdminMemberServiceImpl}）
 * 与真库套件的 {@code @MockitoBean} 都对着**这个类**，保留它 ⇒ 服务层与测试一行都不用改。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class TradeStatClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final TradeStatApi api;

    public TradeStatClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                           OutboundRestClientFactory restClients,
                           @Value("${mall.trade.base-url:lb://mall-trade}") String baseUrl,
                           @Value("${mall.trade.connect-timeout-ms:300}") long connectTimeoutMs,
                           @Value("${mall.trade.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(TradeStatApi.class);
        // 启动日志：活体核对"交易域到底指向哪"（排查"看板为什么全是 0"的第一行）
        log.info("交易域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /** 看板概览的交易口径（今日下单/销售额/待发货/待处理退款） */
    public OrderSummaryVO summary() {
        return unwrap("summary", call(api::summary));
    }

    /** 近 N 天趋势（**连续且补零**由交易域负责；天数钳制也在那边，BFF 原样透传 days） */
    public List<OrderTrendPointVO> trend(int days) {
        return unwrap("trend", call(() -> api.trend(days)));
    }

    /** 销售额榜（有序，商品可能已删除；条数钳制在交易域，BFF 原样透传 limit） */
    public List<SpuAmountVO> topPaidAmountBySpu(int limit) {
        return unwrap("top-amount", call(() -> api.topPaidAmountBySpu(limit)));
    }

    /** 单个会员的订单口径摘要（后台会员**详情**用，与单体同一条端点） */
    public MemberOrderBriefVO memberOrderBrief(long memberId) {
        return unwrap("member-order-brief", call(() -> api.memberOrderBrief(memberId)));
    }

    /**
     * <b>批量</b>会员订单口径摘要（后台会员**列表**用）：一页**一次**调用（规格 §4 无 N+1 判据）。
     *
     * @param memberIds 会员 id；空集合 → 空 Map 且**不发起调用**（空批量没有意义；
     *                  与 {@code ProductClient}/{@code UserCenterClient} 同口径）
     */
    public Map<Long, MemberOrderBriefVO> memberOrderBriefs(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        Map<String, MemberOrderBriefVO> raw =
                unwrap("member-order-brief/batch",
                        call(() -> api.memberOrderBriefs(Map.of("memberIds", List.copyOf(memberIds)))));
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
     * 把"调用接口"这一步的**传输异常**收敛成统一文案。
     *
     * <p>为什么还要这层 try/catch：声明式接口解决的是"HTTP ↔ 类型"的样板，**不解决错误语义**——
     * 连接被拒/超时/读超时抛的是 {@code ResourceAccessException}，非 0 业务码抛的是
     * {@code HttpClientErrorException}（RestClient 默认状态处理器在 4xx/5xx 上抛），
     * 两者都要在这里变成"域的失败"，调用方才知道"这个依赖不可用"。
     */
    private <T> ApiResponse<T> call(java.util.function.Supplier<ApiResponse<T>> invocation) {
        try {
            return invocation.get();
        } catch (Exception e) {
            // debug 而不是 error：看板预期内的降级不该刷错误栈（级别由调用方定，见类注释）
            log.debug("调用交易域失败: err={}", e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
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
