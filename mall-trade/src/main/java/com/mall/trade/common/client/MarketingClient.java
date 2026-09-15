package com.mall.trade.common.client;

import com.mall.trade.common.ApiResponse;
import com.mall.trade.common.BusinessException;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.dto.AdminCouponSaveRequest;
import com.mall.trade.common.dto.AdminCouponVO;
import com.mall.trade.common.dto.CouponBriefVO;
import com.mall.trade.common.dto.CouponChangeResultVO;
import com.mall.trade.common.dto.CouponLockResultVO;
import com.mall.trade.common.dto.CouponRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.mall.common.client.OutboundRestClientFactory;
import com.mall.common.support.MemberId;

/**
 * 营销域出站客户端（P5 步骤 C）：单体对券数据的**唯一**访问方式。
 *
 * <p>它对内替代了原来两个同进程契约（{@code CouponQueryService} / {@code CouponCommandService}）
 * 与后台的 {@code AdminCouponService}——P0 把它们做成"只收发 DTO 的纯接口"就是为了这一天：
 * 调用点只换实现、不换语义。
 *
 * <h2>HTTP 调用改由声明式接口 {@link MarketingApi} 承担</h2>
 * 路径与形状**逐字对齐** {@code mall-marketing} 的 {@code /internal/v1/marketing/**}
 * （路径集中写在 {@link MarketingApi}，本类不再拼字符串）。
 *
 * <p><b>错误处理口径</b>（与 {@link UserCenterClient} 完全一致，不另立一套）：
 * <ul>
 *   <li>业务码非 0 → {@link BusinessException} **原样透传** code/message。
 *       这条对本步尤其关键：{@code 400 未满足优惠券使用门槛}、{@code 409 优惠券已被使用或失效}、
 *       {@code 409 该券已有人领取，无法删除(可停用)} 都是**对外文案**，
 *       一旦被包成 500「系统繁忙」，后台页面与用户提示就变了（C1 基线）；</li>
 *   <li>传输失败 / 空响应 → 500「系统繁忙，请稍后重试」。</li>
 * </ul>
 * 内部密钥 {@code X-Internal-Token} 不再手工写：{@code lb://} 走 {@code @LoadBalanced} builder 上的
 * {@code OutboundHeadersInterceptor}，{@code http://} 直连由
 * {@link OutboundRestClientFactory} 显式挂同一个拦截器。
 *
 * <p>⚠️ <b>超时口径</b>：连接 300ms / 读 2500ms（照 P2 实测：下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。券的调用在下单主链路上，
 * 因此宁可等宽一点，也不要把下游正常响应判成故障→500。
 *
 * <p>{@link #exchange(String, Supplier)} 的签名与日志文案（含 {@code action = "POST " + path}）
 * 与迁移前**逐字相同**。
 */
@Slf4j
@Component
public class MarketingClient {

    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final MarketingApi api;

    public MarketingClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                           OutboundRestClientFactory restClients,
                           @Value("${mall.marketing.base-url:lb://mall-marketing}") String baseUrl,
                           @Value("${mall.marketing.connect-timeout-ms:300}") long connectTimeoutMs,
                           @Value("${mall.marketing.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient restClient = restClients.build(loadBalancedBuilder, baseUrl, connectTimeoutMs, readTimeoutMs);
        this.api = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(MarketingApi.class);
        log.info("营销域客户端就绪(HTTP Interface): base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 交易侧（下单链路） ====================

    /** 抵扣试算（含封顶）；{@code couponMemberId == null} → 0 */
    public long discount(Long memberId, Long couponMemberId, long goodsTotal) {
        Map<String, Object> body = new HashMap<>();
        body.put("memberId", memberId);
        body.put("couponMemberId", couponMemberId);
        body.put("goodsTotal", goodsTotal);
        Long discount = exchange("POST /coupon/discount", () -> api.discount(body));
        return discount == null ? 0L : discount;
    }

    /** 该会员在该金额下可用的券（结算页可选项） */
    public List<CouponBriefVO> usable(Long memberId, long goodsTotal) {
        List<CouponBriefVO> list = exchange("POST /coupon/usable",
                () -> api.usable(Map.of("memberId", memberId, "goodsTotal", goodsTotal)));
        return list == null ? List.of() : list;
    }

    /** 锁定券（{@code 0 → 3}）；抢不到由营销域抛 409「优惠券已被使用或失效」 */
    public boolean lock(Long memberId, Long couponMemberId, String orderNo) {
        CouponLockResultVO result = exchange("POST /coupon/lock",
                () -> api.lock(Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo)));
        return result != null && result.isLocked();
    }

    /**
     * 核销券（<b>支付成功时</b>调用，{@code LOCKED → USED}）。**绝不抛异常**，只返回成败。
     *
     * <p>为什么不抛：调用点在"支付已成功、钱已收"之后。若这里抛出去，支付响应会变成失败，
     * 用户会**重复支付**而订单其实已支付——这比"券没核销成功"严重得多。
     * 因此业务 false（不是本单锁的券）与传输失败都只记日志 + 返回 false，
     * 交给每日对账 / 人工核对兜底（方案 §4.3.1 ③）。这与 {@link #unlockQuietly} 同一口径。
     */
    public boolean use(Long memberId, Long couponMemberId, String orderNo) {
        if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
            return false;
        }
        try {
            CouponChangeResultVO result = exchange("POST /coupon/use",
                    () -> api.use(Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo)));
            boolean changed = result != null && result.isChanged();
            if (!changed) {
                log.warn("券核销未生效(支付已成功，交由对账/人工核对): memberId={} couponMemberId={} orderNo={}",
                        memberId, couponMemberId, orderNo);
            }
            return changed;
        } catch (Exception e) {
            log.error("券核销调用失败(支付已成功，需对账兜底): memberId={} couponMemberId={} orderNo={}",
                    memberId, couponMemberId, orderNo, e);
            return false;
        }
    }

    /**
     * 解锁券（**补偿/关单专用，绝不抛异常**）。
     *
     * <p>为什么吞掉异常：它在 {@code afterCompletion(STATUS_ROLLED_BACK)} 里被调用——
     * 事务已经因为"库存不足/优惠券失效/落单失败"回滚了，此时若因为"解锁也失败了"再抛，
     * 只会把**原始失败原因**盖掉（用户看到的是第二个错误），而且回滚阶段的异常也改不了任何结果。
     * 因此：失败只记 error 日志 + 返回 false，交给每日对账兜底（步骤 E 的 {@code @Scheduled}）。
     * 这与购物车的 {@code restoreCartItems} 是同一个口径。
     */
    public boolean unlockQuietly(Long memberId, Long couponMemberId, String orderNo) {
        try {
            CouponChangeResultVO result = exchange("POST /coupon/unlock",
                    () -> api.unlock(Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo)));
            boolean changed = result != null && result.isChanged();
            if (!changed) {
                log.warn("券解锁未生效(交给对账兜底): memberId={} couponMemberId={} orderNo={}",
                        memberId, couponMemberId, orderNo);
            }
            return changed;
        } catch (Exception e) {
            log.error("券解锁补偿失败(需对账兜底): memberId={} couponMemberId={} orderNo={}",
                    memberId, couponMemberId, orderNo, e);
            return false;
        }
    }

    // ==================== 后台侧（薄转发） ====================

    public PageResult<AdminCouponVO> adminPage(String keyword, Integer status, long pageNum, long pageSize) {
        Map<String, Object> body = new HashMap<>();
        body.put("keyword", keyword);
        body.put("status", status);
        body.put("pageNum", pageNum);
        body.put("pageSize", pageSize);
        return exchange("POST /admin/coupon/page", () -> api.adminPage(body));
    }

    public Long adminCreate(AdminCouponSaveRequest request) {
        return exchange("POST /admin/coupon/create", () -> api.adminCreate(request));
    }

    public void adminUpdate(Long id, AdminCouponSaveRequest request) {
        exchange("POST /admin/coupon/" + id + "/update", () -> api.adminUpdate(id, request));
    }

    public void adminEnable(Long id) {
        exchange("POST /admin/coupon/" + id + "/enable", () -> api.adminEnable(id, Map.of()));
    }

    public void adminDisable(Long id) {
        exchange("POST /admin/coupon/" + id + "/disable", () -> api.adminDisable(id, Map.of()));
    }

    public void adminDelete(Long id) {
        exchange("POST /admin/coupon/" + id + "/delete", () -> api.adminDelete(id, Map.of()));
    }

    public PageResult<CouponRecordVO> adminRecords(Long id, long pageNum, long pageSize) {
        return exchange("POST /admin/coupon/" + id + "/records",
                () -> api.adminRecords(id, Map.of("pageNum", pageNum, "pageSize", pageSize)));
    }

    // ==================== 内部 ====================

    /**
     * 错误语义的唯一落点（迁移前叫 {@code exchange(Supplier, action)}，语义与日志**逐字未变**）：
     * 传输异常/空响应 → 500「系统繁忙，请稍后重试」；业务码非 0 → **原样透传**。
     *
     * <p>⚠️ 具体类型不再需要调用方传 {@code ParameterizedTypeReference}：类型是接口方法签名的一部分
     * （这正是本次迁移要消掉的坑，见 {@link MarketingApi} 的类注释）。
     *
     * @param action 日志标签，形如 {@code "POST /coupon/discount"}（与迁移前同格式）
     */
    private <T> T exchange(String action, Supplier<ApiResponse<T>> invocation) {
        ApiResponse<T> response;
        try {
            response = invocation.get();
        } catch (Exception e) {
            log.error("调用营销域失败: action={}", action, e);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response == null) {
            log.error("调用营销域返回空响应: action={}", action);
            throw new BusinessException(500, DOWNSTREAM_ERROR_MESSAGE);
        }
        if (response.getCode() != ApiResponse.SUCCESS) {
            // 业务错误原样透传（券的 400/409 文案是对外契约的一部分，不能被包成 500）
            throw new BusinessException(response.getCode(), response.getMessage());
        }
        return response.getData();
    }
}
