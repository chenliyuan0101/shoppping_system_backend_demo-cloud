package com.mall.marketing.controller;

import com.mall.marketing.dto.CouponTemplateVO;
import com.mall.marketing.dto.MyCouponVO;
import com.mall.marketing.service.CouponMemberService;
import com.mall.marketing.support.ApiResponse;
import com.mall.common.support.MemberId;
import com.mall.marketing.support.RateLimit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠券 /api/coupon（见《接口文档.md》2.10，**登录**）。
 *
 * <p>P5 批次 2 从单体 {@code com.mall.demo.sms.controller.CouponController}
 * 整体搬进本服务：**路径、方法、参数名、响应 JSON 一律未变**（前端与《接口文档.md》都不用改），
 * 变的只是"谁在服务这些路径"——网关把 {@code /api/coupon/**} 路由到 {@code lb://mall-marketing}。
 *
 * <h2>身份</h2>
 * 三个端点都带 {@code @MemberId}：由 {@code WebConfig} 注册的解析器读**网关注入的头**
 * （{@code X-Gateway-Auth} 凭据校验通过后才信 {@code X-Member-Id}），解析不到一律
 * 401「未登录」。⚠️ 单体里这三个端点同样是"必须登录"（{@code MemberSession.requireUserId}），
 * 因此**匿名访问 401** 是对外行为的一部分，不是本批新加的约束。
 *
 * <h2>限流</h2>
 * 领券端点带着 {@code @RateLimit(scope="coupon_receive", limit=10, windowSeconds=60, by=USER)}
 * ——与单体注解**逐字相同**，键格式 {@code mall:rl:coupon_receive:{identity}} 也与单体一致：
 * 切路由前后是**同一个计数桶**（否则切换那一刻计数清零，等于给刷券留了个窗口）。
 * 身份取"网关注入头 → {@code u{memberId}}"，取不到回落客户端 IP（**不信任 X-Forwarded-For**）。
 */
@RestController
@RequestMapping("/api/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final CouponMemberService couponMemberService;

    /** 券中心：可领取的券模板列表（含"我是否已领"） */
    @GetMapping("/available")
    public ApiResponse<List<CouponTemplateVO>> available(@MemberId Long memberId) {
        return ApiResponse.ok(couponMemberService.available(memberId));
    }

    /**
     * 领券。
     *
     * <p>⚠️ 注解参数必须与单体逐字相同：{@code scope="coupon_receive"}（键名的一部分）、
     * {@code limit=10}、{@code windowSeconds=60}、{@code by=USER}（按会员维度，
     * 解析不到身份时回落 IP）。改任何一个都是改对外行为。
     */
    @PostMapping("/{templateId}/receive")
    @RateLimit(scope = "coupon_receive", limit = 10, windowSeconds = 60, by = RateLimit.By.USER)
    public ApiResponse<Void> receive(@MemberId Long memberId, @PathVariable Long templateId) {
        couponMemberService.receive(memberId, templateId);
        return ApiResponse.ok();
    }

    /** 我的券；{@code status} 空=全部（三态投影见 {@code CouponMemberService#mine}） */
    @GetMapping("/mine")
    public ApiResponse<List<MyCouponVO>> mine(@MemberId Long memberId,
                                              @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(couponMemberService.mine(memberId, status));
    }
}
