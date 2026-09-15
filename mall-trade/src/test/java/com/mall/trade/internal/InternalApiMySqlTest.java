package com.mall.trade.internal;

import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * 内部接口（{@code /internal/**}）的契约与鉴权测试。
 *
 * <p>守两件事：
 * <ol>
 *   <li><b>鉴权必须 fail-closed</b>：没有令牌、令牌错误 → 业务码 403；
 *       这层将来暴露的是"能扣库存、能核销券、能改会员状态"的能力，不能因为漏配而开放。</li>
 *   <li><b>adapter 只是委托</b>：端点返回的数据要与域服务接口一致（这里用真实种子数据抽样断言），
 *       防止将来在这层悄悄塞进业务逻辑。</li>
 * </ol>
 *
 * <p>测试期通过测试基类的 {@code @TestPropertySource} 注入令牌（dev 明文令牌在 application-dev.yaml，
 * 本套件不依赖它）。
 *
 * <p><b>P3-4：{@code /internal/v1/user/**} 的一组断言已经删掉</b>——那些端点连同整份
 * {@code InternalUserController} 一起搬进了 {@code mall-user-center}
 * （会员/地址/购物车数据的属主在那里，单体的内部门面没有存在意义）。
 * 这里保留的那条反向断言（{@link #userInternalApiIsGone()}）就是"搬走"的证据：
 * 单体再收到这些路径必须是 404，而不是"又悄悄实现了一份"。
 */
@SpringBootTest(properties = "mall.internal.token=test-internal-token")
@AutoConfigureMockMvc
class InternalApiMySqlTest extends MySqlTestBase {

    private static final String TOKEN_HEADER = "X-Internal-Token";
    private static final String TOKEN = "test-internal-token";

    /** 种子数据里的 SKU（批量演示数据，见 sql/data） */
    private static final int SEED_SKU_ID = 2001;

    @Test
    @DisplayName("[内部接口] 没带令牌 → 403，且不带任何数据")
    void rejectsWithoutToken() throws Exception {
        mockMvc.perform(get("/internal/v1/stat/summary"))
                .andExpect(status().isOk())                    // HTTP 恒 200，业务码在 body 里
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    @DisplayName("[内部接口] 令牌错误 → 403")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(get("/internal/v1/stat/summary")
                        .header(TOKEN_HEADER, "definitely-wrong"))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("[内部接口] 会员/地址/购物车的内部端点已随 user-center 搬走：单体侧不再有这些路径(404)")
    void userInternalApiIsGone() throws Exception {
        // 这些路径现在由 mall-user-center 提供（网关不路由 /internal/**，服务间直连）。
        // 单体若还留着实现，就会重新变成"两个属主"，所以这里把它钉成业务码 404
        // （没有匹配的 handler → NoResourceFoundException → 统一响应体 code=404「资源不存在」，HTTP 恒 200）。
        mockMvc.perform(get("/internal/v1/user/member/count").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(get("/internal/v1/user/address/default")
                        .header(TOKEN_HEADER, TOKEN).param("memberId", "1"))
                .andExpect(jsonPath("$.code").value(404));
        mockMvc.perform(post("/internal/v1/user/cart/items")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":1,\"itemIds\":[1]}"))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("[内部接口] 商品批量查询：返回契约快照字段（skuId/price/stock/status）")
    void skuBatch() throws Exception {
        mockMvc.perform(post("/internal/v1/product/sku/batch")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + SEED_SKU_ID + "]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].id").value(SEED_SKU_ID))
                .andExpect(jsonPath("$.data[0].price").exists())
                .andExpect(jsonPath("$.data[0].stock").exists())
                .andExpect(jsonPath("$.data[0].status").exists());
    }

    @Test
    @DisplayName("[内部接口] 看板概览：四个订单口径字段齐全")
    void statSummary() throws Exception {
        mockMvc.perform(get("/internal/v1/stat/summary").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.todayOrderCount").isNumber())
                .andExpect(jsonPath("$.data.todaySalesAmount").isNumber())
                .andExpect(jsonPath("$.data.waitShipCount").isNumber())
                .andExpect(jsonPath("$.data.refundPendingCount").isNumber());
    }

    @Test
    @DisplayName("[内部接口] 首页区块：一次往返取回类目树 + 热门 + 新品，且条数受 size 约束")
    void homeFeed() throws Exception {
        mockMvc.perform(get("/internal/v1/product/home-feed")
                        .header(TOKEN_HEADER, TOKEN)
                        .param("size", "2"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.categories.length()").value(greaterThanOrEqualTo(2)))
                // seed 至少 3 个上架商品 → 每块恰为请求的 2 条（口径与前台货架一致）
                .andExpect(jsonPath("$.data.hotProducts.length()").value(2))
                .andExpect(jsonPath("$.data.newProducts.length()").value(2))
                .andExpect(jsonPath("$.data.hotProducts[0].minPrice").isNumber())
                .andExpect(jsonPath("$.data.hotProducts[0].totalStock").isNumber());
    }

    @Test
    @DisplayName("[内部接口] 首页区块：非法 size 回落默认值，不会把整张货架拖出来")
    void homeFeedClampsSize() throws Exception {
        mockMvc.perform(get("/internal/v1/product/home-feed")
                        .header(TOKEN_HEADER, TOKEN)
                        .param("size", "9999"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hotProducts.length()").value(lessThanOrEqualTo(8)))
                .andExpect(jsonPath("$.data.newProducts.length()").value(lessThanOrEqualTo(8)));
    }
}
