package com.mall.marketing.client;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.marketing.support.ApiResponse;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.dto.MemberBriefVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 会员域出站客户端（P5 步骤 C）：营销域取"会员用户名/昵称"的**唯一**方式。
 *
 * <p>路径与形状逐字对齐 user-center 的 {@code POST /internal/v1/user/member/batch}
 * （请求 {@code {memberIds:[...]}}、响应 {@code ApiResponse<List<MemberBriefVO>>}）。
 *
 * <p><b>为什么必须走契约而不是查表</b>：{@code ums_member} 属于会员域（{@code mall_user} 库），
 * P3 之后单体与任何业务服务都不再有它的访问权；营销域的后台"领取记录"需要用户名/昵称，
 * 因此只能问属主。这也是 P0 批次 9 就把这条跨域边改成契约的原因。
 *
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link UserCenterMemberApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练用，由 {@link OutboundRestClientFactory} 显式挂同一个拦截器），
 *       并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：业务码非 0 → {@link BusinessException} 原样透传（下游的 404/400 文案不能变成 500）；
 *       传输失败 / 空响应 → 500「系统繁忙，请稍后重试」（与单体同一文案）；</li>
 *   <li><b>入参形状</b>：空集合 → 空列表且**不发起调用**。</li>
 * </ul>
 *
 * <h2>为什么保留这个类（而不是让调用方直接用接口）</h2>
 * ① 上面那几件事仍需要一个落点；② 调用方（{@code AdminCouponServiceImpl}）与真库套件的
 * {@code @MockitoBean UserCenterMemberClient} 都对着**这个类**，保留它 ⇒ 服务层与测试一行都不用改。
 *
 * <p>⚠️ 超时按 P2 的实测口径取连接 300ms / 读 2500ms（下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。
 *
 * <p>⚠️ 路径前缀 {@code /internal/**}：网关有过滤器直接 404 这一前缀（外网永远看不到）。
 */
@Slf4j
@Component
public class UserCenterMemberClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final UserCenterMemberApi api;

    public UserCenterMemberClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                  OutboundRestClientFactory restClients,
                                  @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                                  @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                                  @Value("${mall.user-center.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(UserCenterMemberApi.class);
        log.info("会员域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * 批量取会员简要信息（用户名/昵称）。
     *
     * @param memberIds 会员 id；空集合 → 空列表（**不发起调用**：空批量没有任何意义，
     *                  而下游对空集合的返回值也无从校验）
     */
    public List<MemberBriefVO> members(Collection<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        List<MemberBriefVO> result = unwrap("/member/batch",
                call("/member/batch", () -> api.memberBatch(Map.of("memberIds", List.copyOf(memberIds)))));
        return result == null ? List.of() : result;
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
    private <T> ApiResponse<T> call(String path, Supplier<ApiResponse<T>> invocation) {
        try {
            return invocation.get();
        } catch (Exception e) {
            log.error("调用会员域失败: action=POST {}", path, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
    }

    /** 空响应按传输失败处理（宁可 500，也不要把 null 当"没有数据"）；非 0 业务码原样透传 */
    private <T> T unwrap(String path, ApiResponse<T> response) {
        if (response == null) {
            log.error("调用会员域返回空响应: action=POST {}", path);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
