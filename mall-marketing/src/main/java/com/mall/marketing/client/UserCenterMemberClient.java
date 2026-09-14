package com.mall.marketing.client;

import com.mall.marketing.support.ApiResponse;
import com.mall.marketing.support.BusinessException;
import com.mall.marketing.support.dto.MemberBriefVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
 * <p>错误处理口径与单体 {@code UserCenterClient} 完全一致（照抄，不另立一套）：
 * <ul>
 *   <li>业务码非 0 → {@link BusinessException} 原样透传（下游的 404/400 文案不能变成 500）；</li>
 *   <li>传输失败 / 空响应 → 500「系统繁忙，请稍后重试」（与单体同一文案）。</li>
 * </ul>
 *
 * <p>⚠️ 超时按 P2 的实测口径取连接 300ms / 读 2500ms（下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。
 */
@Slf4j
@Component
public class UserCenterMemberClient {

    private static final String BASE = "/internal/v1/user";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public UserCenterMemberClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                                  @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                                  @Value("${mall.internal.token:}") String internalToken,
                                  @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                                  @Value("${mall.user-center.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        log.info("会员域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
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
        List<MemberBriefVO> result = post("/member/batch",
                Map.of("memberIds", List.copyOf(memberIds)),
                new ParameterizedTypeReference<ApiResponse<List<MemberBriefVO>>>() {
                });
        return result == null ? List.of() : result;
    }

    // ==================== 内部 ====================

    /**
     * ⚠️ 具体类型必须由调用方显式传入（见下面 {@code ParameterizedTypeReference} 的用法）：
     * 泛型方法里的 {@code T} 会被擦除，Jackson 只能反序列化成 {@code LinkedHashMap}，
     * 调用方随后 {@code ClassCastException} —— 这个 bug 编译期看不出来（P3-4 实测踩到）。
     */
    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        ApiResponse<T> response;
        try {
            response = restClient.post()
                    .uri(BASE + path)
                    .header("X-Internal-Token", internalToken)
                    .body(body)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.error("调用会员域失败: action=POST {}", path, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
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
