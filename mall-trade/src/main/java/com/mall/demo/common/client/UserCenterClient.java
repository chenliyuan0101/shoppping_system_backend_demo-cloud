package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.AddressSnapshotVO;
import com.mall.demo.common.dto.CartClaimResultVO;
import com.mall.demo.common.dto.CartItemSnapshotVO;
import com.mall.demo.common.dto.MemberBriefVO;
import com.mall.demo.common.dto.MemberSnapshotVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 用户中心出站客户端（P3-4）：单体对会员/地址/购物车数据的**唯一**访问方式。
 *
 * <p>它对齐的是"单体内原来那 4 个域服务接口"（{@code MemberQueryService} / {@code MemberAdminService} /
 * {@code CartCheckoutService} / {@code AddressQueryService}）——P0 把它们做成"只收发 DTO 的纯接口"，
 * 就是为了这一天：调用点一行不用改，只把实现从"查本地表"换成"调 HTTP"。
 *
 * <p>路径与形状**逐字对齐** {@code mall-user-center} 的 {@code /internal/v1/user/**}
 * （原单体的 {@code InternalUserController} 平移到那边），因此切换当天只改调用方，不改契约。
 *
 * <p>购物车结算两阶段（P3 的购物车闸门改造，见方案 §5 P3）：
 * {@link #claimCartItems} 以 {@code orderNo} 为幂等键领取购物车明细；订单事务回滚时由调用方
 * 显式调用 {@link #restoreCartItems} 归还——**不再依赖"远程删除会被本地事务回滚"**（那个前提已失效）。
 */
@Slf4j
@Component
public class UserCenterClient {

    private static final String BASE = "/internal/v1/user";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public UserCenterClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                            @Value("${mall.user-center.base-url:lb://mall-user-center}") String baseUrl,
                            @Value("${mall.internal.token:}") String internalToken,
                            @Value("${mall.user-center.connect-timeout-ms:300}") long connectTimeoutMs,
                            @Value("${mall.user-center.read-timeout-ms:2000}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        log.info("用户中心客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 会员 ====================

    public MemberBriefVO member(long memberId) {
        return get("/member/{id}", new ParameterizedTypeReference<ApiResponse<MemberBriefVO>>() {
        }, memberId);
    }

    /**
     * 会员**完整**快照（后台会员详情用）：{@code {id,username,nickname,phone,avatar,status,createTime}}。
     *
     * <p>为什么不复用 {@link #member(long)}：那个是"简要"（{@code MemberBriefVO}，给订单列表补名字用），
     * 只有 id/username/nickname/phone。后台详情页要 avatar/status/createTime，用简要快照会静默丢字段。
     *
     * <p>⚠️ 这段注释是踩坑记录：最初版本没有这个端点，远程实现只好用
     * {@code /member/page?keyword=<id>} 反查——只有"用户名/手机号/昵称里含这串数字"才命中，
     * 其他情况直接 404。**按 id 取详情必须有按 id 的契约**，用检索接口反查是错误的等价替换。
     *
     * @return 会员不存在（含逻辑删除）→ {@code null}（不是异常：调用方据此返回 404 语义）
     */
    public MemberSnapshotVO memberSnapshot(long memberId) {
        return get("/member/{id}/snapshot", new ParameterizedTypeReference<ApiResponse<MemberSnapshotVO>>() {
        }, memberId);
    }
    public long memberCount() {
        Long count = get("/member/count", new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
        return count == null ? 0L : count;
    }

    public List<MemberBriefVO> members(Collection<Long> memberIds) {
        return post("/member/batch", Map.of("memberIds", emptyIfNull(memberIds)),
                new ParameterizedTypeReference<ApiResponse<List<MemberBriefVO>>>() {
                });
    }

    public List<Long> searchMemberIds(String keyword) {
        return post("/member/search-ids", Map.of("keyword", keyword == null ? "" : keyword),
                new ParameterizedTypeReference<ApiResponse<List<Long>>>() {
                });
    }

    /**
     * 会员分页（后台列表）。
     *
     * <p>主查必须留在属主域：分页的 total 与 list 要在同一个库、同一时刻算出来，
     * 否则"总数 200 但翻到第 3 页是空的"这类问题会变成常态。
     */
    public PageResult<MemberSnapshotVO> memberPage(String keyword, Integer status,
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

    /** 管理员改会员状态（属主域内保证"禁用即失效令牌"这条不变量） */
    public void updateMemberStatus(long memberId, int status) {
        exchange(() -> restClient.post().uri(BASE + "/member/{id}/status", memberId)
                .header(InternalApiHeaders.TOKEN, internalToken)
                .body(Map.of("status", status))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<Void>>() {
                }), "updateMemberStatus");
    }

    // ==================== 地址 ====================

    /** 默认地址；不存在返回 null（下单时按"请选择收货地址"处理） */
    public AddressSnapshotVO defaultAddress(long memberId) {
        return get("/address/default?memberId={id}", new ParameterizedTypeReference<ApiResponse<AddressSnapshotVO>>() {
        }, memberId);
    }

    /** 指定地址；不存在或不属于该会员 → null（不区分两者，避免探测他人地址） */
    public AddressSnapshotVO address(long memberId, long addressId) {
        return get("/address/{aid}?memberId={mid}", new ParameterizedTypeReference<ApiResponse<AddressSnapshotVO>>() {
        }, addressId, memberId);
    }

    // ==================== 购物车（结算闸门两阶段） ====================

    /** 读要结算的购物车条目（不含价格——价格必须由商品域现算） */
    public List<CartItemSnapshotVO> cartItems(long memberId, Collection<Long> itemIds) {
        return post("/cart/items", Map.of("memberId", memberId, "itemIds", emptyIfNull(itemIds)),
                new ParameterizedTypeReference<ApiResponse<List<CartItemSnapshotVO>>>() {
                });
    }

    /**
     * 结算闸门（幂等领取）：清空这些明细并返回是否领到。
     *
     * <p>幂等键是 {@code orderNo}：**同一订单重试 → 视为已领取**（返回 claimed=true 与当时的明细快照），
     * 而不是"0 行 → 已被别人结算"。这样"用户双击提交"不会因为第二次拿不到闸门而误报 409，
     * 也不会双扣库存（库存由商品域按 orderNo 幂等）。
     * 不同订单抢同一批明细时，仍然只有一方拿到。
     */
    public CartClaimResultVO claimCartItems(long memberId, String orderNo, Collection<Long> itemIds) {
        return post("/cart/claim",
                Map.of("memberId", memberId, "orderNo", orderNo, "itemIds", emptyIfNull(itemIds)),
                new ParameterizedTypeReference<ApiResponse<CartClaimResultVO>>() {
                });
    }

    /**
     * 补偿：把某个订单已领取的明细还回购物车（订单事务回滚时调用）。
     *
     * <p>幂等：重复调用不会重复归还；没有领取记录时是空操作。**失败不抛异常**——
     * 补偿发生在事务回滚阶段，此时再抛异常只会掩盖原始失败原因；失败要留日志并由对账兜底。
     */
    public boolean restoreCartItems(long memberId, String orderNo) {
        try {
            Boolean restored = post("/cart/restore", Map.of("memberId", memberId, "orderNo", orderNo),
                    new ParameterizedTypeReference<ApiResponse<Boolean>>() {
                    });
            return Boolean.TRUE.equals(restored);
        } catch (Exception e) {
            log.error("购物车明细补偿失败(需对账兜底): memberId={} orderNo={}", memberId, orderNo, e);
            return false;
        }
    }


    // ==================== 内部 ====================

    /**
     * GET 的统一入口。
     *
     * <p>⚠️ <b>必须由调用方传入具体的 {@code ParameterizedTypeReference}</b>，不能在这里用
     * {@code ParameterizedTypeReference<ApiResponse<T>>}：泛型方法里的 {@code T} 会被擦除，
     * Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，于是调用方拿到
     * {@code ClassCastException: LinkedHashMap cannot be cast to ...}。
     * 这个 bug **编译期完全看不出来**，只在运行时炸——P3-4 切远程模式时实测踩到
     * （后台会员详情 500），因此这里把类型显式化并留下注释。
     */
    private <T> T get(String uriTemplate, ParameterizedTypeReference<ApiResponse<T>> type, Object... uriVariables) {
        return exchange(() -> restClient.get()
                .uri(BASE + uriTemplate, uriVariables)
                .header(InternalApiHeaders.TOKEN, internalToken)
                .retrieve()
                .body(type), "GET " + uriTemplate);
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        return exchange(() -> restClient.post()
                .uri(BASE + path)
                .header(InternalApiHeaders.TOKEN, internalToken)
                .body(body)
                .retrieve()
                .body(type), "POST " + path);
    }

    private <T> T exchange(Supplier<ApiResponse<T>> invocation, String action) {
        ApiResponse<T> response;
        try {
            response = invocation.get();
        } catch (Exception e) {
            log.error("调用用户中心失败: action={}", action, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用用户中心返回空响应: action={}", action);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 业务错误原样透传（404「会员不存在」之类不能变成 500）
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }

    private static <T> List<T> emptyIfNull(Collection<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    /** 内部凭据头名（与两侧 InternalApiAuthInterceptor 的常量一致） */
    private static final class InternalApiHeaders {
        private static final String TOKEN = "X-Internal-Token";

        private InternalApiHeaders() {
        }
    }
}
