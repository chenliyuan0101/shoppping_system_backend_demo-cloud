package com.mall.admin.controller;

import com.mall.admin.support.DownstreamStubs;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>降级矩阵第 1 格：交易域（{@code mall-legacy}）不可用。</b>
 *
 * <p>做法：把 {@code mall.trade.base-url} 指向 {@code http://127.0.0.1:9}（保留端口，没人监听
 * ⇒ 连接被拒），product / user-center 仍指向环回桩。断言见
 * {@link AbstractDashboardDeadDependencyTest}（summary 的交易四字段为 0、其余域仍是真值、
 * trend 与 top?type=amount 退化为空数组、HTTP 200 / code=0 / 键路径集合不变、每请求一条 WARN）。
 */
@DisplayName("P7 降级矩阵：交易域不可用（死端口）")
class AdminDashboardTradeDownTest extends AbstractDashboardDeadDependencyTest {

    @DynamicPropertySource
    static void pointTradeAtDeadPort(DynamicPropertyRegistry registry) {
        wireWithDead(DownstreamStubs.TRADE.name, registry);
    }

    @Override
    protected String deadDomain() {
        return DownstreamStubs.TRADE.name;
    }
}
