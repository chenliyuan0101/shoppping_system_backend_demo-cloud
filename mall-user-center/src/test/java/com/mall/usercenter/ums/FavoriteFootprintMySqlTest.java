package com.mall.usercenter.ums;

import com.mall.usercenter.client.ProductSnapshotClient;
import com.mall.usercenter.support.UserCenterTestBase;
import com.mall.usercenter.support.constant.EnableStatus;
import com.mall.usercenter.support.dto.SpuSnapshotVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 收藏 + 浏览足迹 真库测试（{@code mall_user}，真实提交 + 清理）。
 *
 * <p>与单体 {@code com.mall.demo.ums.FavoriteFootprintMySqlTest} 的差别只有两处（与本批次的改造一一对应）：
 * 身份改走网关注入头（{@link #asMember}）、商品数据改由商品域出站客户端提供（{@code @MockitoBean}）。
 * 业务断言（收藏开关、商品不存在 404、足迹去重刷新、下架静默不记录、清空）逐条保留。
 */
class FavoriteFootprintMySqlTest extends UserCenterTestBase {

    private static final long SPU_ON_SALE = 1001L;
    private static final long SPU_OTHER = 1002L;
    private static final long SPU_MISSING = 999999L;
    private static final long MIN_PRICE = 129900L;

    @MockitoBean
    private ProductSnapshotClient productSnapshotClient;

    private long memberId;

    @BeforeEach
    void setUp() {
        when(productSnapshotClient.spu(SPU_ON_SALE)).thenReturn(
                new SpuSnapshotVO(SPU_ON_SALE, "集成测试手机", "副标题", "/img/spu/1001.png", 88, EnableStatus.ENABLED));
        when(productSnapshotClient.spu(SPU_OTHER)).thenReturn(
                new SpuSnapshotVO(SPU_OTHER, "集成测试耳机", null, "/img/spu/1002.png", 12, EnableStatus.ENABLED));
        // 不存在 / 已下架：商品域明确回答"没有"（不是"下游挂了"）
        when(productSnapshotClient.spu(SPU_MISSING)).thenReturn(null);
        when(productSnapshotClient.spus(anyCollection())).thenReturn(List.of(
                new SpuSnapshotVO(SPU_ON_SALE, "集成测试手机", "副标题", "/img/spu/1001.png", 88, EnableStatus.ENABLED),
                new SpuSnapshotVO(SPU_OTHER, "集成测试耳机", null, "/img/spu/1002.png", 12, EnableStatus.ENABLED)));
        when(productSnapshotClient.minEnabledSkuPrices(anyCollection()))
                .thenReturn(Map.of(SPU_ON_SALE, MIN_PRICE, SPU_OTHER, 9900L));

        memberId = insertMember("uc_fav_");
    }

    @AfterEach
    void cleanUp() {
        deleteMember(memberId);
    }

    @Test
    @DisplayName("[MySQL] 收藏开关 + 我的收藏")
    void favoriteFlow() throws Exception {
        // 收藏
        mockMvc.perform(asMember(post("/api/favorite/toggle"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_ON_SALE + "}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.favorited").value(true));

        mockMvc.perform(asMember(get("/api/favorite/page"), memberId))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.list[0].spuId").value(SPU_ON_SALE))
                .andExpect(jsonPath("$.data.list[0].title").value("集成测试手机"))
                .andExpect(jsonPath("$.data.list[0].mainImage").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].price").value(MIN_PRICE))
                .andExpect(jsonPath("$.data.list[0].sales").value(88))
                .andExpect(jsonPath("$.data.list[0].createTime").isNotEmpty());

        // 再收藏一次 = 取消
        mockMvc.perform(asMember(post("/api/favorite/toggle"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_ON_SALE + "}"))
                .andExpect(jsonPath("$.data.favorited").value(false));
        mockMvc.perform(asMember(get("/api/favorite/page"), memberId))
                .andExpect(jsonPath("$.data.total").value(0));

        // 不存在/下架商品收藏 → 404
        mockMvc.perform(asMember(post("/api/favorite/toggle"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_MISSING + "}"))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("商品不存在或已下架"));
    }

    @Test
    @DisplayName("[MySQL] 足迹记录去重刷新 + 分页 + 清空")
    void footprintFlow() throws Exception {
        // 两次浏览同一商品只保留一条
        mockMvc.perform(asMember(post("/api/footprint/record"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_ON_SALE + "}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(post("/api/footprint/record"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_ON_SALE + "}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(post("/api/footprint/record"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_OTHER + "}"))
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(asMember(get("/api/footprint/page"), memberId))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.list[0].spuId").isNumber())
                .andExpect(jsonPath("$.data.list[0].title").isNotEmpty())
                .andExpect(jsonPath("$.data.list[0].price").isNumber())
                .andExpect(jsonPath("$.data.list[0].lastViewTime").isNotEmpty());

        // 不存在的商品静默不记录（不抛错、不写行）
        mockMvc.perform(asMember(post("/api/footprint/record"), memberId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_MISSING + "}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/footprint/page"), memberId))
                .andExpect(jsonPath("$.data.total").value(2));

        // 清空
        mockMvc.perform(asMember(delete("/api/footprint"), memberId))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(asMember(get("/api/footprint/page"), memberId))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("[MySQL] 未登录访问收藏/足迹 → 401")
    void requireLogin() throws Exception {
        mockMvc.perform(get("/api/favorite/page")).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/footprint/page")).andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(post("/api/favorite/toggle")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"spuId\":" + SPU_ON_SALE + "}"))
                .andExpect(jsonPath("$.code").value(401));
    }
}
