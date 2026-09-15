package com.mall.content.service;

import com.mall.content.client.ProductFeedClient;
import com.mall.content.client.ProductFeedUnavailableException;
import com.mall.content.support.CacheKeys;
import com.mall.content.support.ContentTestBase;
import com.mall.content.support.dto.CategoryNode;
import com.mall.content.support.dto.HomeFeedVO;
import com.mall.content.support.dto.ProductListItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.CacheService;

/**
 * 首页聚合（跨服务调用 + 降级）的测试。
 *
 * <p>这是 P2 唯一"跨进程调用"的路径，因此这里是整套 P2 里最该被测死的一段：
 * <ol>
 *   <li><b>正常</b>：商品区块来自商品域的一次调用（{@code size=8}），与 Banner/公告拼成一个响应；</li>
 *   <li><b>降级</b>：商品域不可用 → HTTP 200 + {@code code=0}，只返回 Banner/公告，
 *       三个商品区块是**空数组**（不是 null）——前端空数据兜底依赖这一点；</li>
 *   <li><b>降级不放大故障</b>：故障期间降级结果短缓存，不把每个请求都变成一次远程调用；</li>
 *   <li><b>正常缓存</b>：45 秒内只调一次商品域（首页是最高频入口，不能每次请求都跨进程）。</li>
 * </ol>
 *
 * <p>用 {@code @MockitoBean} 替换商品域客户端：这几个断言测的是"本服务的聚合与降级逻辑"，
 * 不应该依赖另一个服务是否在跑（那是联调/契约测试的事，见 P2 的降级演练）。
 */
class HomeAggregationMySqlTest extends ContentTestBase {

    @MockitoBean
    private ProductFeedClient productFeedClient;

    @BeforeEach
    void clearHomeCache() {
        try {
            redisTemplate.delete(CacheKeys.homeIndex());
        } catch (Exception ignored) {
            // Redis 未启动：CacheService 自己会 fail-open，这里不用管
        }
    }

    @Test
    @DisplayName("[首页] 商品区块来自商品域一次调用（size=8），并与 Banner/公告拼成一个响应")
    void aggregatesProductFeed() throws Exception {
        when(productFeedClient.homeFeed(anyInt())).thenReturn(feed());

        mockMvc.perform(get("/api/home/index"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 本域内容（真库 seed：≥2 轮播、≥1 公告）
                .andExpect(jsonPath("$.data.banners.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.notices.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                // 商品域来的三块
                .andExpect(jsonPath("$.data.categories[0].name").value("手机数码"))
                .andExpect(jsonPath("$.data.hotProducts[0].spuId").value(1001))
                .andExpect(jsonPath("$.data.hotProducts[0].minPrice").value(29900))
                .andExpect(jsonPath("$.data.newProducts[0].title").value("新品标题"));

        ArgumentCaptor<Integer> size = ArgumentCaptor.forClass(Integer.class);
        verify(productFeedClient).homeFeed(size.capture());
        assertEquals(8, size.getValue(), "首页每个区块固定 8 条（改造前 SECTION_SIZE）");
    }

    @Test
    @DisplayName("[降级] 商品域不可用 → 首页 code=0，只有 Banner/公告，三个商品区块为空数组")
    void degradesWhenProductUnavailable() throws Exception {
        when(productFeedClient.homeFeed(anyInt()))
                .thenThrow(new ProductFeedUnavailableException("read timed out"));

        mockMvc.perform(get("/api/home/index"))
                .andExpect(status().isOk())                       // 绝不 5xx
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.banners").isArray())
                .andExpect(jsonPath("$.data.notices").isArray())
                .andExpect(jsonPath("$.data.categories").isEmpty())
                .andExpect(jsonPath("$.data.hotProducts").isEmpty())
                .andExpect(jsonPath("$.data.newProducts").isEmpty());
    }

    @Test
    @DisplayName("[降级] 故障期间不放大故障：降级结果按 5 秒短缓存，连续请求只打一次商品域")
    void degradedResultIsCachedBriefly() throws Exception {
        assumeTrue(redisUp(), "Redis 未启动，跳过缓存断言（CacheService fail-open 时每次都会重试下游）");
        when(productFeedClient.homeFeed(anyInt()))
                .thenThrow(new ProductFeedUnavailableException("connection refused"));

        mockMvc.perform(get("/api/home/index")).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/home/index")).andExpect(jsonPath("$.code").value(0));

        verify(productFeedClient, times(1)).homeFeed(anyInt());
    }

    @Test
    @DisplayName("[缓存] 正常结果按 45 秒缓存：连续两次请求只打一次商品域")
    void normalResultIsCached() throws Exception {
        assumeTrue(redisUp(), "Redis 未启动，跳过缓存断言");
        when(productFeedClient.homeFeed(anyInt())).thenReturn(feed());

        mockMvc.perform(get("/api/home/index")).andExpect(jsonPath("$.data.hotProducts").isNotEmpty());
        mockMvc.perform(get("/api/home/index")).andExpect(jsonPath("$.data.hotProducts").isNotEmpty());

        verify(productFeedClient, times(1)).homeFeed(anyInt());
    }

    @Test
    @DisplayName("[缓存] 首页整份聚合来自 Redis：整段替换缓存后接口返回受控内容（JSON 往返可用）")
    void homeServedFromRedisSentinel() throws Exception {
        assumeTrue(redisUp(), "Redis 未启动，跳过缓存断言");
        String sentinel = "缓存哨兵-9f3a";
        // 只放一个哨兵类目：若接口返回它，说明"确实读了缓存且反序列化成功"；返回真实数据则说明缓存没被用上
        setKey(CacheKeys.homeIndex(),
                "{\"banners\":[],\"notices\":[],\"categories\":[{\"id\":9903,\"parentId\":0,\"name\":\""
                        + sentinel + "\",\"sort\":1,\"status\":1,\"children\":[]}],"
                        + "\"hotProducts\":[],\"newProducts\":[]}",
                Duration.ofSeconds(30));

        mockMvc.perform(get("/api/home/index"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.banners").isEmpty())
                .andExpect(jsonPath("$.data.hotProducts").isEmpty())
                .andExpect(jsonPath("$.data.categories[0].name").value(sentinel));

        // 命中缓存时不该再跨进程调商品域
        verify(productFeedClient, times(0)).homeFeed(anyInt());
    }

    @Test
    @DisplayName("[缓存] 后台写内容 → 首页缓存被删且下次请求立即包含新内容（前台零延迟可见）")
    void writeInvalidatesCacheAndHomeShowsNewBanner() throws Exception {
        when(productFeedClient.homeFeed(anyInt())).thenReturn(feed());

        // 先让首页缓存建立起来
        mockMvc.perform(get("/api/home/index")).andExpect(jsonPath("$.code").value(0));

        // 通过内部接口新增一条轮播（P2 期间这条路由由单体转发过来）
        String title = "首页可见性_" + suffix;
        MvcResult created = mockMvc.perform(post("/internal/v1/content/banner")
                        .header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"imageUrl\":\"http://img/new.jpg\",\"sort\":1}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long bannerId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.data")).longValue();

        try {
            mockMvc.perform(get("/api/home/index"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.banners[?(@.id==" + bannerId + ")].title").value(title));
        } finally {
            jdbcTemplate.update("DELETE FROM cms_banner WHERE id = ?", bannerId);
        }
    }

    /** 商品域返回的样例数据（形状与 {@code GET /internal/v1/product/home-feed} 一致） */
    private static HomeFeedVO feed() {
        CategoryNode category = new CategoryNode();
        category.setId(1L);
        category.setParentId(0L);
        category.setName("手机数码");

        ProductListItemVO hot = new ProductListItemVO();
        hot.setSpuId(1001L);
        hot.setTitle("热门商品");
        hot.setMinPrice(29900L);

        ProductListItemVO fresh = new ProductListItemVO();
        fresh.setSpuId(1002L);
        fresh.setTitle("新品标题");

        return HomeFeedVO.builder()
                .categories(List.of(category))
                .hotProducts(List.of(hot))
                .newProducts(List.of(fresh))
                .build();
    }
}
