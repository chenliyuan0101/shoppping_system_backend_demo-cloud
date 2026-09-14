package com.mall.marketing.service;

import com.mall.marketing.dto.CouponTemplateVO;
import com.mall.marketing.dto.MyCouponVO;

import java.util.List;

/**
 * 会员侧券服务契约（券中心 / 领券 / 我的券）——对外公开端点
 * {@code /api/coupon/**} 的业务实现。
 *
 * <p><b>与 {@link CouponQueryService}/{@link CouponCommandService} 的分工</b>（不要混）：
 * <ul>
 *   <li>本接口：**面向会员的 HTTP 面**，{@code memberId} 来自网关注入的身份
 *       （{@code @MemberId} → {@code GatewayIdentityResolver}），经网关暴露给前端；</li>
 *   <li>{@code CouponQueryService}/{@code CouponCommandService}：**面向服务间的内部面**
 *       （{@code /internal/v1/marketing/coupon/**}，给 trade 用），{@code memberId}
 *       是调用方在服务端上下文里传进来的。</li>
 * </ul>
 * 两条链路的"券规则"共用同一份 {@code CouponRules}（不复制第二套判断），
 * 但**入参来源与鉴权方式完全不同**——这正是它们不能合成一个接口的原因。
 *
 * <p>本接口逐行搬自单体 {@code com.mall.demo.sms.service.CouponService}
 * （P5 批次 2）：校验顺序、错误码、文案、并发防超发逻辑一律未改。
 * 单体的 {@code usableForTotal} **没有搬**（死代码，见下）。
 */
public interface CouponMemberService {

    /**
     * 可领取券模板列表：启用的模板 + 在有效领取窗口内，并标注"我是否已领过"。
     *
     * <p>⚠️ 单体里 {@code memberId} 可以是 null（"未登录也能看券中心"），但**那条路走不到**：
     * 单体的 {@code @MemberId} 走 {@code MemberSession.requireUserId}，匿名一律 401。
     * 本服务同理（{@code @MemberId} → {@code GatewayIdentityResolver} → 401「未登录」），
     * 所以这里保留了 {@code memberId == null} 的容忍分支（逐行搬，不改口径），
     * 但它在 HTTP 面是不可达的。
     */
    List<CouponTemplateVO> available(Long memberId);

    /**
     * 领券。
     *
     * <p>文案逐字（C1）：404「券不存在或已停发」、409「不在领取时间内」、
     * 409「已达每人限领数量」、409「券已被领完」、409「已领取过该券」。
     *
     * <p>⚠️ 单体的 {@code usableForTotal} 是死代码（全仓无调用点），**刻意没有搬**：
     * 它承担的"结算页可用券"能力现在由 {@code CouponQueryService.usableCoupons} 提供
     * （下单确认链路用），搬过来等于把没人用的规则再复制一份。
     */
    void receive(Long memberId, Long templateId);

    /**
     * 我的券（{@code status} 0未用 1已用 2过期，空=全部）。
     *
     * <p><b>三态投影（P5 批次 1 定下的 C1 硬约束，本批第一次真正生效）</b>：
     * <ul>
     *   <li>过滤：{@code 0 → 只筛 0}；{@code 1 → IN (1,3)}（锁定中的券必须出现在"已使用"页，
     *       否则用户会以为券凭空消失）；{@code 2 → 只筛 2}；空 → 不过滤；</li>
     *   <li>响应：库里 {@code 3(LOCKED)} 输出 {@code 1(已使用)}——旧实现里下单瞬间券就已 USED，
     *       锁定窗口内对外必须仍是"已使用"。</li>
     * </ul>
     */
    List<MyCouponVO> mine(Long memberId, Integer status);
}
