package com.mall.admin.support;

import com.mall.admin.service.AdminDashboardService;
import com.mall.admin.service.AdminMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * P7 后半（看板/会员）用例的公共基类：真库（mall_admin）+ 真 Redis + MockMvc + 环回桩下游。
 *
 * <h2>为什么继承 {@link AdminTestBase}</h2>
 * 身份链路（网关注入头 / 令牌版本 / sys_user）与其它套件必须**完全同一套口径**：
 * 看板与会员端点都在 {@code /api/admin/**} 下，被同一个 {@code AdminAuthInterceptor} 拦。
 * 自己另建一套鉴权夹具，等于让"鉴权"这件事出现第二个版本。
 *
 * <h2>每个用例开始时的两件事</h2>
 * <ol>
 *   <li>{@code DownstreamStubs.resetAll()}：模式/慢速/计数/在途峰值全部清零
 *       —— 判据里有"调用次数"，不清理就会把上一个用例的调用算进来；</li>
 *   <li>{@code dashboardCache.evictAll()}：看板缓存是**跨上下文共享的真 Redis**，
 *       而"代"是全局键。不换代的话，另一个用例类（甚至另一个测试上下文）写下的
 *       {@code v{N}:summary} 会被本类读到 —— 那是**假绿/假红**的经典来源
 *       （本该打到桩上的请求变成了缓存命中）。换代使旧代的键立刻不可达，TTL 到期自然回收。</li>
 * </ol>
 */
public abstract class BffStubTestBase extends AdminTestBase {

    @Autowired
    protected AdminDashboardService adminDashboardService;

    @Autowired
    protected AdminMemberService adminMemberService;

    @Autowired
    protected DashboardCache dashboardCache;

    /** 本用例的管理员 id（真库插入，用例结束由基类物理删除） */
    protected long adminId;

    @BeforeEach
    void resetStubsAndCacheAndCreateAdmin() {
        DownstreamStubs.resetAll();
        dashboardCache.evictAll();
        adminId = insertAdmin("p7bff_", 1);
    }

    /** 给请求装上"网关注入的身份"（看板/会员端点的生产形态） */
    protected MockHttpServletRequestBuilder gw(MockHttpServletRequestBuilder builder) {
        return asGatewayAdmin(builder, adminId, 0L);
    }
}
