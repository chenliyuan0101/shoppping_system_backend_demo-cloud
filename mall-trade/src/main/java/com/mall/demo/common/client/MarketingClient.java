package com.mall.demo.common.client;

import com.mall.demo.common.ApiResponse;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.dto.CouponBriefVO;
import com.mall.demo.common.dto.CouponChangeResultVO;
import com.mall.demo.common.dto.CouponLockResultVO;
import com.mall.demo.common.dto.AdminCouponSaveRequest;
import com.mall.demo.common.dto.AdminCouponVO;
import com.mall.demo.common.dto.CouponRecordVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 营销域出站客户端（P5 步骤 C）：单体对券数据的**唯一**访问方式。
 *
 * <p>它对内替代了原来两个同进程契约（{@code CouponQueryService} / {@code CouponCommandService}）
 * 与后台的 {@code AdminCouponService}——P0 把它们做成"只收发 DTO 的纯接口"就是为了这一天：
 * 调用点只换实现、不换语义。
 *
 * <p>路径与形状**逐字对齐** {@code mall-marketing} 的 {@code /internal/v1/marketing/**}：
 * <ul>
 *   <li>交易侧：{@code /coupon/discount}、{@code /coupon/usable}、{@code /coupon/lock}、{@code /coupon/unlock}</li>
 *   <li>后台：{@code /admin/coupon/{page,create,{id}/update,{id}/enable,{id}/disable,{id}/delete,{id}/records}}</li>
 * </ul>
 *
 * <p><b>错误处理口径</b>（与 {@link UserCenterClient} 完全一致，不另立一套）：
 * <ul>
 *   <li>业务码非 0 → {@link BusinessException} **原样透传** code/message。
 *       这条对本步尤其关键：{@code 400 未满足优惠券使用门槛}、{@code 409 优惠券已被使用或失效}、
 *       {@code 409 该券已有人领取，无法删除(可停用)} 都是**对外文案**，
 *       一旦被包成 500「系统繁忙」，后台页面与用户提示就变了（C1 基线）；</li>
 *   <li>传输失败 / 空响应 → 500「系统繁忙，请稍后重试」。</li>
 * </ul>
 *
 * <p>⚠️ <b>超时口径</b>：连接 300ms / 读 2500ms（照 P2 实测：下游冷启动首调 1.98s，
 * 照抄"目标值 300ms"会把正常请求判成故障）。券的调用在下单主链路上，
 * 因此宁可等宽一点，也不要把下游正常响应判成故障→500。
 */
@Slf4j
@Component
public class MarketingClient {

    private static final String BASE = "/internal/v1/marketing";
    private static final String DOWNSTREAM_ERROR_MESSAGE = "系统繁忙，请稍后重试";

    private final RestClient restClient;
    private final String internalToken;

    public MarketingClient(@LoadBalanced RestClient.Builder loadBalancedBuilder,
                           @Value("${mall.marketing.base-url:lb://mall-marketing}") String baseUrl,
                           @Value("${mall.internal.token:}") String internalToken,
                           @Value("${mall.marketing.connect-timeout-ms:300}") long connectTimeoutMs,
                           @Value("${mall.marketing.read-timeout-ms:2500}") long readTimeoutMs) {
        RestClient.Builder builder = baseUrl.startsWith("lb://") ? loadBalancedBuilder : RestClient.builder();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.internalToken = internalToken;
        log.info("营销域客户端就绪: base-url={}, connect-timeout={}ms, read-timeout={}ms",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    // ==================== 交易侧（下单链路） ====================

    /** 抵扣试算（含封顶）；{@code couponMemberId == null} → 0 */
    public long discount(Long memberId, Long couponMemberId, long goodsTotal) {
        Map<String, Object> body = new HashMap<>();
        body.put("memberId", memberId);
        body.put("couponMemberId", couponMemberId);
        body.put("goodsTotal", goodsTotal);
        Long discount = post("/coupon/discount", body, new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
        return discount == null ? 0L : discount;
    }

    /** 该会员在该金额下可用的券（结算页可选项） */
    public List<CouponBriefVO> usable(Long memberId, long goodsTotal) {
        List<CouponBriefVO> list = post("/coupon/usable", Map.of("memberId", memberId, "goodsTotal", goodsTotal),
                new ParameterizedTypeReference<ApiResponse<List<CouponBriefVO>>>() {
                });
        return list == null ? List.of() : list;
    }

    /** 锁定券（{@code 0 → 3}）；抢不到由营销域抛 409「优惠券已被使用或失效」 */
    public boolean lock(Long memberId, Long couponMemberId, String orderNo) {
        CouponLockResultVO result = post("/coupon/lock",
                Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo),
                new ParameterizedTypeReference<ApiResponse<CouponLockResultVO>>() {
                });
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
            CouponChangeResultVO result = post("/coupon/use",
                    Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo),
                    new ParameterizedTypeReference<ApiResponse<CouponChangeResultVO>>() {
                    });
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
            CouponChangeResultVO result = post("/coupon/unlock",
                    Map.of("memberId", memberId, "couponMemberId", couponMemberId, "orderNo", orderNo),
                    new ParameterizedTypeReference<ApiResponse<CouponChangeResultVO>>() {
                    });
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
        return post("/admin/coupon/page", body,
                new ParameterizedTypeReference<ApiResponse<PageResult<AdminCouponVO>>>() {
                });
    }

    public Long adminCreate(AdminCouponSaveRequest request) {
        return post("/admin/coupon/create", request, new ParameterizedTypeReference<ApiResponse<Long>>() {
        });
    }

    public void adminUpdate(Long id, AdminCouponSaveRequest request) {
        post("/admin/coupon/" + id + "/update", request, new ParameterizedTypeReference<ApiResponse<Void>>() {
        });
    }

    public void adminEnable(Long id) {
        post("/admin/coupon/" + id + "/enable", Map.of(), new ParameterizedTypeReference<ApiResponse<Void>>() {
        });
    }

    public void adminDisable(Long id) {
        post("/admin/coupon/" + id + "/disable", Map.of(), new ParameterizedTypeReference<ApiResponse<Void>>() {
        });
    }

    public void adminDelete(Long id) {
        post("/admin/coupon/" + id + "/delete", Map.of(), new ParameterizedTypeReference<ApiResponse<Void>>() {
        });
    }

    public PageResult<CouponRecordVO> adminRecords(Long id, long pageNum, long pageSize) {
        return post("/admin/coupon/" + id + "/records", Map.of("pageNum", pageNum, "pageSize", pageSize),
                new ParameterizedTypeReference<ApiResponse<PageResult<CouponRecordVO>>>() {
                });
    }

    // ==================== 内部 ====================

    /**
     * POST 的统一入口。
     *
     * <p>⚠️ <b>具体类型必须由调用方传入</b>：泛型方法里的 {@code T} 会被擦除，
     * Jackson 只能把 {@code data} 反序列化成 {@code LinkedHashMap}，调用方随后
     * {@code ClassCastException}——编译期看不出来，只在运行时炸（P3-4 实测踩到，
     * 当时表现为"后台会员详情 500"）。因此每一处都用显式的 {@code ParameterizedTypeReference}。
     */
    private <T> T post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        return exchange(() -> restClient.post()
                .uri(BASE + path)
                .header("X-Internal-Token", internalToken)
                .body(body)
                .retrieve()
                .body(type), "POST " + path);
    }

    private <T> T exchange(Supplier<ApiResponse<T>> invocation, String action) {
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
