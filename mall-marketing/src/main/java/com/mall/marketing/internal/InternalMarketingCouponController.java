package com.mall.marketing.internal;

import com.mall.marketing.dto.CouponChangeResult;
import com.mall.marketing.dto.CouponDiscountRequest;
import com.mall.marketing.dto.CouponLockRequest;
import com.mall.marketing.dto.CouponLockResult;
import com.mall.marketing.dto.CouponUsableRequest;
import com.mall.marketing.dto.CouponUseRequest;
import com.mall.marketing.service.CouponCommandService;
import com.mall.marketing.service.CouponQueryService;
import com.mall.marketing.support.ApiResponse;
import com.mall.marketing.support.RequestValidator;
import com.mall.marketing.support.dto.CouponBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 营销域内部接口（服务间调用，给 trade 用）。{@code /internal/**} 由
 * {@code InternalApiAuthInterceptor} 守：没有 {@code X-Internal-Token} 一律 403。
 *
 * <h2>五个端点与它们的调用时机（批次 4 的 trade 侧要按这个顺序用）</h2>
 * <ol>
 *   <li>{@code usable}：结算页/下单前——"这个会员在这个金额下能用哪些券"；</li>
 *   <li>{@code discount}：下单算钱——"这张券抵多少"（含封顶）；</li>
 *   <li>{@code lock}：下单事务内——占用券（{@code 0 → 3}）。<b>抢不到即 409，下单失败</b>；</li>
 *   <li>{@code use}：支付成功回调——核销（{@code 3 → 1}）。失败只记日志，**不得让支付失败**；</li>
 *   <li>{@code unlock}：下单失败补偿 / 取消 / 超时关单——解锁（{@code 3 → 0}）。</li>
 * </ol>
 *
 * <h2>为什么全部是 POST</h2>
 * 这些端点都带 {@code memberId} 与金额/单号这类业务数据，用 GET + query 会把它们写进
 * 访问日志与浏览器历史；且 {@code lock/use/unlock} 是写操作，语义上不该是 GET
 * （网关/中间层的 GET 重试会变成重复写）。除了 {@code usable} 之外它们也都不是"取资源"。
 *
 * <h2>返回形状</h2>
 * 一律 {@code {code,message,data}} 且 **HTTP 恒 200**（见 {@code ApiResponse}）：
 * 券用不了时是 200 + {@code code=409}，<b>调用方必须按 code 判成败</b>——
 * 若只看 HTTP 状态码，会把"券已被别人用掉"当成成功，静默少收钱或多抵扣。
 *
 * <h2>校验放在哪</h2>
 * 每个方法先调 {@link RequestValidator#check(Object)}（DTO 上的约束 → 400 + 原中文提示），
 * 再委托 Service：这样"HTTP 面"与"被直接调用的 Service"得到一致的参数校验口径
 * （{@code @Valid} 只在 Controller 层生效，Service 层调用时完全不触发）。
 * 券的 5 条业务文案则由 Service 抛 {@code BusinessException}——**校验顺序即契约**，
 * 不在这里重排。
 */
@RestController
@RequestMapping("/internal/v1/marketing/coupon")
@RequiredArgsConstructor
public class InternalMarketingCouponController {

    private final CouponQueryService couponQueryService;

    private final CouponCommandService couponCommandService;

    private final RequestValidator requestValidator;

    /**
     * 可用券列表（会员维度 + 本单金额门槛）。
     *
     * <p>只返回 {@code coupon_status = 0} 的券：**锁定中的券不是可用券**（已被某一单占用）。
     */
    @PostMapping("/usable")
    public ApiResponse<List<CouponBriefVO>> usable(@RequestBody CouponUsableRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(couponQueryService.usableCoupons(request.getMemberId(), request.getGoodsTotal()));
    }

    /**
     * 算抵扣额（分，含封顶）。
     *
     * <p>{@code couponMemberId} 为 null = 本单不用券 → {@code data: 0}（正常路径，不是错误）。
     */
    @PostMapping("/discount")
    public ApiResponse<Long> discount(@RequestBody CouponDiscountRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(couponQueryService.discountFor(
                request.getMemberId(), request.getCouponMemberId(), request.getGoodsTotal()));
    }

    /**
     * 锁定券（{@code UNUSED → LOCKED}，写 {@code order_no}）。幂等：同一订单重复 lock 返回 true。
     *
     * @throws com.mall.marketing.support.BusinessException 409「优惠券已被使用或失效」
     *         （被别人锁住/已核销/已过期状态）——文案与旧实现逐字相同
     */
    @PostMapping("/lock")
    public ApiResponse<CouponLockResult> lock(@RequestBody CouponLockRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(CouponLockResult.of(
                couponCommandService.lock(request.getMemberId(), request.getCouponMemberId(), request.getOrderNo())));
    }

    /**
     * 核销券（{@code LOCKED → USED}，写 {@code use_time}）。幂等。
     *
     * <p>{@code changed=false} 表示这张券不是本单锁的——调用方（支付回调）**只记日志**，
     * 不得让支付失败（方案 §4.3.1 ③）。
     */
    @PostMapping("/use")
    public ApiResponse<CouponChangeResult> use(@RequestBody CouponUseRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(CouponChangeResult.of(
                couponCommandService.use(request.getMemberId(), request.getCouponMemberId(), request.getOrderNo())));
    }

    /**
     * 解锁券（{@code LOCKED → UNUSED}，清 {@code order_no}）。幂等；**只动自己锁的那张**。
     *
     * <p>{@code changed=false} 表示这张券不是本单锁的（被别人锁着/已被核销）——
     * 与 {@code use} 同样只记日志，把"锁死的券"交给批次 5 的每日对账兜底。
     */
    @PostMapping("/unlock")
    public ApiResponse<CouponChangeResult> unlock(@RequestBody CouponUseRequest request) {
        requestValidator.check(request);
        return ApiResponse.ok(CouponChangeResult.of(
                couponCommandService.unlock(request.getMemberId(), request.getCouponMemberId(), request.getOrderNo())));
    }
}
