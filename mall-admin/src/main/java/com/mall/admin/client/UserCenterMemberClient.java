package com.mall.admin.client;

import com.mall.common.client.OutboundRestClientFactory;
import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.dto.MemberSnapshotVO;
import com.mall.admin.support.dto.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

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
 * <h2>P8-7 起：HTTP 调用改由声明式接口 {@link UserCenterMemberApi} 承担</h2>
 * 本类保留**域语义**三件事，其余交给接口：
 * <ul>
 *   <li><b>连接装配</b>：{@code lb://} 走服务发现（{@code @LoadBalanced} 的 builder，其上已挂
 *       {@code OutboundHeadersInterceptor} 统一注入 {@code X-Internal-Token} / {@code X-Trace-Id}）、
 *       {@code http://} 直连（排障/演练用），并设置 connect/read 超时；</li>
 *   <li><b>错误语义</b>：传输失败/空响应 → {@code BusinessException(500, 系统繁忙，请稍后重试)}；
 *       下游业务码非 0 → **原样透传**；</li>
 *   <li><b>请求体形状适配</b>：分页请求体的字段名与值的格式（ISO 时间串）在这里组装
 *       ——"发出去的请求必须与单体逐字一致"这条判据的落点。</li>
 * </ul>
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
        // 启动日志：活体核对"会员域到底指向哪"（排查"看板会员数为什么全是 0"的第一行）
        log.info("会员域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
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
        return unwrap("member/page", call(() -> api.page(body)));
    }

    /**
     * 会员档案快照（详情用）。
     *
     * <p>不存在（含逻辑删除）→ {@code null}（**不是异常**：调用方据此返回 404 语义）。
     * 按 id 取详情必须有按 id 的契约——用 {@code /member/page?keyword=id} 反查是错的等价替换
     * （单体 {@code UserCenterClient.memberSnapshot} 的踩坑注释）。
     */
    public MemberSnapshotVO snapshot(long memberId) {
        return unwrap("member/{id}/snapshot", call(() -> api.snapshot(memberId)));
    }

    /** 会员总数（看板 summary 的 {@code memberCount}） */
    public long count() {
        Long count = unwrap("member/count", call(api::count));
        return count == null ? 0L : count;
    }

    /**
     * 改会员状态（后台启停）。属主域内保证"禁用即失效令牌"，BFF 不参与。
     *
     * <p>返回 {@code code=0} 时，"旧令牌立刻不可用"这件事已经发生——所以本方法的调用方
     * <b>只需</b>处理自己的副作用（看板缓存失效），不需要、也不该再 bump 任何版本号。
     */
    public void updateStatus(long memberId, int status) {
        unwrap("member/{id}/status", call(() -> api.updateStatus(memberId, Map.of("status", status))));
    }

    // ==================== 内部（口径同 TradeStatClient） ====================

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
            // debug 而不是 error：降级是调用方的策略，不该在这里刷错误栈（级别由调用方定，见类注释）
            log.debug("调用会员域失败: err={}", e.toString());
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
    }

    /** 空响应按传输失败处理（宁可 500，也不要把 null 当"没有数据"）；非 0 业务码原样透传 */
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
