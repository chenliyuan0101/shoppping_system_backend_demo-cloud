package com.mall.admin.controller;

import com.mall.admin.support.DownstreamStubs;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>降级矩阵第 3 格：会员域（{@code mall-user-center}）不可用。</b>
 *
 * <p>做法：把 {@code mall.user-center.base-url} 指向 {@code http://127.0.0.1:9}，
 * trade / product 仍指向环回桩。断言见 {@link AbstractDashboardDeadDependencyTest}
 * （summary 的 {@code memberCount} 为 0 而交易/商品两域仍是真值；
 * trend 与两个榜单**完全不受影响**：会员域不是它们的依赖 ⇒ 元素键路径集合逐字不变）。
 *
 * <p>⚠️ 本格也是"**不影响**的依赖挂了"的对照格：如果哪天有人把用户域的调用塞进 trend/top 的
 * 关键路径上，那些断言会立刻红——这正是"矩阵要按依赖逐个跑"而不是"只测一个"的原因。
 */
@DisplayName("P7 降级矩阵：会员域不可用（死端口）")
class AdminDashboardUserDownTest extends AbstractDashboardDeadDependencyTest {

    @DynamicPropertySource
    static void pointUserCenterAtDeadPort(DynamicPropertyRegistry registry) {
        wireWithDead(DownstreamStubs.USER.name, registry);
    }

    @Override
    protected String deadDomain() {
        return DownstreamStubs.USER.name;
    }
}
