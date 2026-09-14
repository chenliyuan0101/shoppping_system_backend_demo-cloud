package com.mall.admin.controller;

import com.mall.admin.support.DownstreamStubs;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * <b>降级矩阵第 2 格：商品域（{@code mall-product}）不可用。</b>
 *
 * <p>做法：把 {@code mall.product.base-url} 指向 {@code http://127.0.0.1:9}，
 * trade / user-center 仍指向环回桩。断言见 {@link AbstractDashboardDeadDependencyTest}
 * （summary 的 {@code onShelfProductCount} 为 0 而交易/会员两域仍是真值；
 * 销量榜与销售额榜都退化为空数组——销售额榜需要商品域补标题，见实现类注释）。
 */
@DisplayName("P7 降级矩阵：商品域不可用（死端口）")
class AdminDashboardProductDownTest extends AbstractDashboardDeadDependencyTest {

    @DynamicPropertySource
    static void pointProductAtDeadPort(DynamicPropertyRegistry registry) {
        wireWithDead(DownstreamStubs.PRODUCT.name, registry);
    }

    @Override
    protected String deadDomain() {
        return DownstreamStubs.PRODUCT.name;
    }
}
