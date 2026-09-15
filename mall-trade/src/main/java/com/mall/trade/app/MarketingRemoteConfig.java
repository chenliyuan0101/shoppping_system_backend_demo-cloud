package com.mall.trade.app;

import com.mall.trade.common.client.MarketingClient;
import com.mall.trade.common.contract.CouponCommandService;
import com.mall.trade.common.contract.CouponQueryService;
import com.mall.trade.common.dto.CouponBriefVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import com.mall.common.support.MemberId;

/**
 * 营销域接线（P5 步骤 C）：把两个券契约实现成"调 mall-marketing 的内部接口"。
 *
 * <p><b>没有本地实现</b>：步骤 C 与"单体不再有任何券表读写"是同一步——本地实现与表访问一起删掉了。
 * 这里**没有**任何"查本地表"的退路。
 *
 * <p><b>为什么有一个 {@code mall.marketing.remote} 属性开关</b>（照 {@link UserCenterRemoteConfig}
 * 的同一套做法）：它**不是生产回退开关**，而是给测试留的**替换点**——测试进程里没有 marketing 可调，
 * 需要有地方把契约换成测试替身（{@code src/test/.../support/MarketingTestDoubleConfig}）。
 * 生产恒为 {@code true}（{@code matchIfMissing=true}）；若有人显式关掉它，
 * 结果是"没有 {@code CouponQueryService} 这个 bean" → **启动直接失败**（fail-fast），
 * 而不是悄悄退回某条没人维护的老路径。
 *
 * <p><b>为什么必须在组装根 {@code app}</b>：只有组装根有权知道"哪些契约由哪个服务实现"
 * （P3 的 ArchUnit 规则 B6 会拦"业务域直接依赖别的域的实现"）。
 *
 * <p><b>为什么是 class 而不是 record</b>：record 是 final 类，Spring 需要为它生成 CGLIB 代理时
 * 会直接启动失败（P3-4 实测踩过 {@code Could not generate CGLIB subclass ... final class}）。
 */
@Configuration
@ConditionalOnProperty(name = "mall.marketing.remote", havingValue = "true", matchIfMissing = true)
public class MarketingRemoteConfig {

    @Bean
    public CouponQueryService couponQueryService(MarketingClient client) {
        return new RemoteCouponQuery(client);
    }

    @Bean
    public CouponCommandService couponCommandService(MarketingClient client) {
        return new RemoteCouponCommand(client);
    }

    // ==================== 实现 ====================

    /** 券查询契约 → {@code /coupon/discount}、{@code /coupon/usable} */
    public static class RemoteCouponQuery implements CouponQueryService {

        private final MarketingClient client;

        public RemoteCouponQuery(MarketingClient client) {
            this.client = client;
        }

        @Override
        public long discountFor(Long memberId, Long couponMemberId, long goodsTotal) {
            if (memberId == null) {
                return 0L;
            }
            // ⚠️ "本单不用券"在**调用方短路**，不发远程调用：
            //   · 契约上等价——营销域的 discountFor 对 couponMemberId==null 同样返回 0（那条分支仍在营销域里），
            //     这里只是把"没有券可算"这件事在本地判掉；
            //   · 换来两件实事：① **不用券的下单/结算预览不依赖 marketing 可用性**
            //     （券服务抖动不该让所有人下不了单）；② 省掉一次无意义的跨进程往返（预览每次都会调它）。
            //   实测教训：不短路时任何"不用券的预览"都会打营销域，测试环境（没有 marketing 实例）
            //   直接 500「系统繁忙」，把 OrderFlowMySqlTest 这类与券完全无关的套件打红。
            if (couponMemberId == null) {
                return 0L;
            }
            return client.discount(memberId, couponMemberId, goodsTotal);
        }

        @Override
        public List<CouponBriefVO> usableCoupons(Long memberId, long goodsTotal) {
            return memberId == null ? List.of() : client.usable(memberId, goodsTotal);
        }
    }

    /** 券命令契约 → {@code /coupon/lock}、{@code /coupon/use}、{@code /coupon/unlock}（核销与补偿都不抛异常） */
    public static class RemoteCouponCommand implements CouponCommandService {

        private final MarketingClient client;

        public RemoteCouponCommand(MarketingClient client) {
            this.client = client;
        }

        @Override
        public boolean lock(Long memberId, Long couponMemberId, String orderNo) {
            if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
                return false;
            }
            return client.lock(memberId, couponMemberId, orderNo);
        }

        @Override
        public boolean use(Long memberId, Long couponMemberId, String orderNo) {
            if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
                return false;
            }
            // 走"绝不抛异常"的版本：调用点在支付成功之后，这里抛出去会把支付判成失败
            return client.use(memberId, couponMemberId, orderNo);
        }

        @Override
        public boolean unlock(Long memberId, Long couponMemberId, String orderNo) {
            if (memberId == null || couponMemberId == null || orderNo == null || orderNo.isBlank()) {
                return false;
            }
            return client.unlockQuietly(memberId, couponMemberId, orderNo);
        }
    }
}
