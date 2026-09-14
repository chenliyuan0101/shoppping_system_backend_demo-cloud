package com.mall.content.service;

import com.mall.content.client.ProductFeedClient;
import com.mall.content.support.ContentTestBase;
import com.mall.content.support.dto.CategoryNode;
import com.mall.content.support.dto.HomeFeedVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Redis 故障降级（fail-open）下首页仍然可用。
 *
 * <p>P2 把 {@code HomeServiceImpl} 从单体搬到本服务时，原先单体 {@code RedisFailOpenMySqlTest}
 * 里"Redis 挂了首页照常出内容"那条断言也一并迁过来——它在微服务形态下价值更高：
 * 首页现在还要跨进程取商品区块，若 Redis 故障时再抖一次，用户看到的就是白屏。
 *
 * <p>做法：把 {@code spring.data.redis.port} 指到一个没有服务的端口。此时
 * {@code CacheService} 按 fail-open 退化为"无缓存"（读返回 null → 回落 DB/远程），
 * 因此这里断言的是"没有缓存也能拼出完整响应"。
 */
@org.springframework.boot.test.context.SpringBootTest(properties = {
        "mall.internal.token=test-internal-token",
        "spring.data.redis.port=6399",
        "spring.data.redis.timeout=500ms",
        "spring.data.redis.connect-timeout=500ms",
        "mall.cache.enabled=true"
})
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class HomeFailOpenMySqlTest extends ContentTestBase {

    @MockitoBean
    private ProductFeedClient productFeedClient;

    @Test
    @DisplayName("[Redis 降级] Redis 不可用：首页仍返回 Banner/公告 + 商品区块")
    void homeWorksWithoutRedis() throws Exception {
        CategoryNode category = new CategoryNode();
        category.setId(1L);
        category.setName("手机数码");
        when(productFeedClient.homeFeed(anyInt())).thenReturn(HomeFeedVO.builder()
                .categories(List.of(category))
                .hotProducts(List.of())
                .newProducts(List.of())
                .build());

        mockMvc.perform(get("/api/home/index"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.banners").isArray())
                .andExpect(jsonPath("$.data.notices").isArray())
                .andExpect(jsonPath("$.data.categories[0].name").value("手机数码"));
    }
}
