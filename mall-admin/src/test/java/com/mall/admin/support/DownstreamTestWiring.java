package com.mall.admin.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 把三个出站目标（trade / product / user-center）接到环回桩或"死端口"上的统一接线。
 *
 * <h2>为什么要单独一个类</h2>
 * 每个用例类都必须自己声明 {@code @DynamicPropertySource}（**不能放在父类里继承**：
 * 继承来的静态方法会和子类自己那份同时生效，同一个 key 出现两个来源时，
 * 到底谁赢取决于注册顺序 —— 这类"配置竞态"会让"依赖不可用"的用例偶尔变成"其实依赖是好的"）。
 * 声明要各写一份，但**值的口径只应该有一份**，所以把"接哪三个地址、超时多少、内部令牌是什么"收在这里。
 *
 * <p>{@link #DEAD_URL} 指向 127.0.0.1:9（保留端口，本机没有任何进程监听）：
 * 这就是本项目"模拟某个服务挂了"的标准手法（mall-product 的 {@code ProductTestBase} 同款），
 * 不需要停任何进程 —— 与"不许启停进程"的纪律相容。
 */
public final class DownstreamTestWiring {

    private DownstreamTestWiring() {
    }

    /** "服务不在"的地址：保留端口 9，连接直接被拒（不启动、不停止任何进程） */
    public static final String DEAD_URL = "http://127.0.0.1:9";

    /** 与 stub 断言的出站凭据（两侧 InternalApiAuthInterceptor 的约定头） */
    public static final String INTERNAL_TOKEN = "test-internal-token";

    /**
     * 读超时用 600ms（生产默认 2500ms）：让"下游停半路 ⇒ 读超时"的用例只花 0.6 秒而不是 2.5 秒。
     * 连接超时保持 300ms（生产同值）。
     */
    public static final String READ_TIMEOUT_MS = "600";

    public static void wire(DynamicPropertyRegistry registry, String tradeUrl, String productUrl, String userUrl) {
        registry.add("mall.trade.base-url", () -> tradeUrl);
        registry.add("mall.product.base-url", () -> productUrl);
        registry.add("mall.user-center.base-url", () -> userUrl);
        registry.add("mall.internal.token", () -> INTERNAL_TOKEN);
        registry.add("mall.trade.read-timeout-ms", () -> READ_TIMEOUT_MS);
        registry.add("mall.product.read-timeout-ms", () -> READ_TIMEOUT_MS);
        registry.add("mall.user-center.read-timeout-ms", () -> READ_TIMEOUT_MS);
    }

    /** 三个域都指向环回桩（"依赖都在"的场景） */
    public static void wireAllToStubs(DynamicPropertyRegistry registry) {
        wire(registry, DownstreamStubs.TRADE.url(), DownstreamStubs.PRODUCT.url(), DownstreamStubs.USER.url());
    }
}
