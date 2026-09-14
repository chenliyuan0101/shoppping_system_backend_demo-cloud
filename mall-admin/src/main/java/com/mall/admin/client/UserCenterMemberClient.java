package com.mall.admin.client;

import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.MemberSnapshotVO;
import com.mall.admin.support.dto.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * <b>会员域</b>（{@code mall-user-center}）出站客户端：后台会员列表/详情/启停的唯一访问方式。
 *
 * <h2>形状逐字对齐单体 {@code com.mall.demo.common.client.UserCenterClient}</h2>
 * 路径、请求体字段名、值的格式（{@code LocalDateTime#toString()} 的 ISO 形式）都照抄——
 * 因为"切换前后 user-center 收到的请求必须是同一个请求"，否则分页/过滤结果会悄悄变
 * （例如把 {@code 2026-09-01T00:00} 写成 {@code 2026-09-01 00:00:00}，Jackson 侧的解析就会不同）。
 *
 * <h2>错误语义（C1：与单体同文案）</h2>
 * <ul>
 *   <li>传输失败 / 空响应 → {@code BusinessException(500, "系统繁忙，请稍后重试")}；</li>
 *   <li>下游业务码非 0 → **原样透传**：{@code 404 会员不存在}、{@code 400 状态值仅支持 0禁用 1正常}
 *       必须活着到前端，不能被包成 500（这是单体 {@code UserCenterClient} 的注释原文口径）。</li>
 * </ul>
 *
 * <h2>为什么"禁用/启用"也走本类</h2>
 * 那条不变量（<b>禁用 ⇒ 会员令牌版本 +1</b>）的归属在会员域内（{@code MemberAdminService.updateStatus}），
 * BFF 只发一次写请求。若在 BFF 侧顺手 bump 一个本地版本号，就会出现"两处都在管会员登录态"，
 * 而其中一处永远不知道自己漏了什么（P0/P3 反复强调过的失配形态）。
 *
 * <p>日志纪律与 {@link TradeStatClient} 相同：本类只 {@code debug}，级别由调用方定。
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
     * 会员分页（后台列表的**分页主查**）。
     *
     * <p>主查必须留在属主域：分页的 total 与 list 要在同一个库、同一时刻算出来，
     * 否则"总数 200 但翻到第 3 页是空的"这类问题会变成常态（单体注释原文）。
     * 时间区间用 {@code LocalDateTime#toString()} 传，与单体逐字一致。
     */
    public PageResult<MemberSnapshotVO> page(String keyword, Integer status,
                                             LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                             long pageNum, long pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("createTimeStart", createTimeStart == null ? null : createTimeStart.toString());
        body.put("createTimeEnd", createTimeEnd == null ? null : createTimeEnd.toString());
        body.put("status", status);
        body.put("pageNum", pageNum);
        body.put("pageSize", pageSize);
        return post("/member/page", body,
                new ParameterizedTypeReference<ApiResponse<PageResult<MemberSnapshotVO>>>() {
                });
    }

    /**
     * 会员档案快照（详情用）。
     *
     * <p>不存在（含逻辑删除）→ {@code null}（**不是异常**：调用方据此返回 404 语义）。
     * 按 id 取详情必须有按 id 的契约——用 {@code /member/page?keyword=id} 反查是错的等价替换
     * （单体 {@code UserCenterClient.memberSnapshot} 的踩坑注释）。
     */
    public MemberSnapshotVO snapshot(long memberId) {
        return get("/member/{id}/snapshot", new ParameterizedTypeReference<ApiResponse<MemberSnapshotVO>>() {
        }, memberId);
    }

    /** 会员总数（看板 summary 的 {@code memberCount}） */
    public long count() {
        Long count = get("/member/count", new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
        return count == null ? 0L : count;
    }

    /**
     * 改会员状态（后台启停）。属主域内保证"禁用即失效令牌"，BFF 不参与。
     *
     * <p>返回 {@code code=0} 时，"旧令牌立刻不可用"这件事已经发生——所以本方法的调用方
     * <b>只需</b>处理自己的副作用（看板缓存失效），不需要、也不该再 bump 任何版本号。
     */
    public void updateStatus(long memberId, int status) {
        post("/member/" + memberId + "/status", Map.of("status", status),
                new ParameterizedTypeReference<ApiResponse<Void>>() {
                });
    }

    // ==================== 内部 ====================

    private <T> T get(String uriTemplate, ParameterizedTypeReference<ApiResponse<T>> type, Object... uriVariables) {
        ApiResponse<T> response;
        try {
            response = restClient.get().uri(BASE + uriTemplate, uriVariables)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(type);
        } catch (Exception e) {
            log.debug("调用会员域失败: uri={} err={}", uriTemplate, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(uriTemplate, response);
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
            log.debug("调用会员域失败: path={} err={}", path, e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        return unwrap(path, response);
    }

    private <T> T unwrap(String uri, ApiResponse<T> response) {
        if (response == null) {
            log.debug("调用会员域返回空响应: uri={}", uri);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 404「会员不存在」/ 400「状态值仅支持 0禁用 1正常」原样透传到前端（C1）
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
