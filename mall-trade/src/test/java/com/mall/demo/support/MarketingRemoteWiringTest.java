package com.mall.demo.support;

import com.mall.demo.app.MarketingRemoteConfig;
import com.mall.demo.common.contract.CouponCommandService;
import com.mall.demo.common.contract.CouponQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>接线守卫测试</b>：断言测试上下文里券契约用的**确实是替身**，而不是真的远程实现。
 *
 * <h2>为什么需要它（这是"静默失败"的守卫）</h2>
 * 若生产接线配置（{@code app.MarketingRemoteConfig}）**没有条件注解**，
 * {@code @AutoConfiguration} 的 {@code @ConditionalOnMissingBean} 会在用户配置之后看到"bean 已存在"
 * 而**静默退让**：替身不生效、不报错、不打 warning，测试期照样走真 HTTP 客户端，
 * 于是**任何走 {@code /api/order/preview} 的套件**都会 500「系统繁忙」，
 * 而券自己的套件因为用 {@code @MockitoBean} 强制替换**仍然是绿的**——
 * 这是最难定位的一类组合（P5 步骤 C 实测踩到，坑的完整说明写在
 * {@link MarketingTestDoubleConfig} 的类注释里，P6/P7 每个新服务都会遇到）。
 *
 * <p>本用例把"替身真的接上了"变成**可执行断言**：只要有人去掉
 * {@code @ConditionalOnProperty} 或删掉 {@code mall.marketing.remote=false}，这里立刻红，
 * 而不是等到某个无关套件以一个看不懂的 500 报出来。
 */
@SpringBootTest
class MarketingRemoteWiringTest extends MySqlTestBase {

    @Autowired
    private CouponQueryService couponQueryService;

    @Autowired
    private CouponCommandService couponCommandService;

    @Test
    @DisplayName("[接线守卫] 测试期券查询契约必须是替身（不是 MarketingClient 的远程实现）")
    void couponQueryContractIsTestDouble() {
        assertThat(couponQueryService).isInstanceOf(MarketingTestDoubleConfig.NoCouponQuery.class);
        assertThat(couponQueryService).isNotInstanceOf(MarketingRemoteConfig.RemoteCouponQuery.class);
        // 默认语义：没有可用券、不用券不抵扣（与"本机没有 marketing"时的确定性行为一致）
        assertThat(couponQueryService.usableCoupons(1L, 10000L)).isEmpty();
        assertThat(couponQueryService.discountFor(1L, null, 10000L)).isZero();
    }

    @Test
    @DisplayName("[接线守卫] 测试期券命令契约必须是替身（不是 MarketingClient 的远程实现）")
    void couponCommandContractIsTestDouble() {
        assertThat(couponCommandService).isInstanceOf(MarketingTestDoubleConfig.AlwaysOkCommand.class);
        assertThat(couponCommandService).isNotInstanceOf(MarketingRemoteConfig.RemoteCouponCommand.class);
        assertThat(couponCommandService.lock(1L, 1L, "T-GUARD")).isTrue();
        assertThat(couponCommandService.unlock(1L, 1L, "T-GUARD")).isTrue();
    }
}