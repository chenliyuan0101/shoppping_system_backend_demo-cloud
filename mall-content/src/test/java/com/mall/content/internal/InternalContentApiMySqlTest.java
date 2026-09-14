package com.mall.content.internal;

import com.jayway.jsonpath.JsonPath;
import com.mall.content.support.CacheKeys;
import com.mall.content.support.ContentTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容域内部接口的真库测试（P2 决策 1 的"业务断言迁到属主服务"）。
 *
 * <p>它承接了单体 {@code AdminContentMySqlTest} 的全部业务断言（轮播/公告 CRUD + 前台可见性联动），
 * 但改走 {@code /internal/v1/content/**}：在 P2 的架构里，**业务与数据的断言属于属主服务**，
 * 单体那一侧只保留"鉴权 + 参数校验 + 错误透传"的转发契约断言（见单体的 {@code ContentForwardMySqlTest}）。
 *
 * <p>额外补了三类断言（都是这次拆分才需要守的）：
 * <ol>
 *   <li><b>内部接口 fail-closed</b>：没令牌/令牌错误 → 403，且不能改数据；</li>
 *   <li><b>错误文案逐字不变</b>：400/404 的文案是客户端可见契约（C1），拆服务时最容易被改掉；</li>
 *   <li><b>写操作必删首页缓存</b>：{@code mall:cache:home:index} 是内容域与商品域共有的 key（§2.9 第 5 条）。</li>
 * </ol>
 */
class InternalContentApiMySqlTest extends ContentTestBase {

    private final List<Long> banners = new ArrayList<>();
    private final List<Long> notices = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : banners) {
            jdbcTemplate.update("DELETE FROM cms_banner WHERE id = ?", id);
        }
        for (Long id : notices) {
            jdbcTemplate.update("DELETE FROM cms_notice WHERE id = ?", id);
        }
    }

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("[内部接口] 没带令牌 → 403，且不能改数据")
    void rejectsWithoutToken() throws Exception {
        long before = countBanners();
        mockMvc.perform(post("/internal/v1/content/banner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"无令牌\",\"imageUrl\":\"http://img/x.jpg\"}"))
                .andExpect(status().isOk())                  // HTTP 恒 200，业务码在 body
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.data").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(before, countBanners(), "鉴权失败却写入了数据");
    }

    @Test
    @DisplayName("[内部接口] 令牌错误 → 403")
    void rejectsWrongToken() throws Exception {
        mockMvc.perform(get("/internal/v1/content/banner/page").header(TOKEN_HEADER, "definitely-wrong"))
                .andExpect(jsonPath("$.code").value(403));
    }

    // ==================== 业务全流程（原单体的断言） ====================

    @Test
    @DisplayName("[MySQL] 轮播/公告 全流程 + 前台可见性联动（内部接口）")
    void contentFlow() throws Exception {
        // ---- 轮播 ----
        long bannerId = createBanner("测试轮播_" + suffix);

        mockMvc.perform(get("/internal/v1/content/banner/page").header(TOKEN_HEADER, TOKEN)
                        .param("keyword", suffix))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));

        // 前台可见（新增默认启用）—— 公开读路径就在本服务
        mockMvc.perform(get("/api/banner/list"))
                .andExpect(jsonPath("$.data[?(@.id==" + bannerId + ")].title").value("测试轮播_" + suffix));

        // 停用 → 前台消失
        mockMvc.perform(put("/internal/v1/content/banner/" + bannerId + "/status").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/banner/list"))
                .andExpect(jsonPath("$.data[?(@.id==" + bannerId + ")]").isEmpty());

        // 修改后再启用
        mockMvc.perform(put("/internal/v1/content/banner/" + bannerId).header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"测试轮播改_" + suffix + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(put("/internal/v1/content/banner/" + bannerId + "/status").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/banner/list"))
                .andExpect(jsonPath("$.data[?(@.id==" + bannerId + ")].title").value("测试轮播改_" + suffix));

        // ---- 公告 ----
        long noticeId = createNotice("测试公告_" + suffix);
        mockMvc.perform(get("/internal/v1/content/notice/page").header(TOKEN_HEADER, TOKEN)
                        .param("keyword", suffix))
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/notice/list"))
                .andExpect(jsonPath("$.data[?(@.id==" + noticeId + ")].title").value("测试公告_" + suffix));

        mockMvc.perform(put("/internal/v1/content/notice/" + noticeId + "/status").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/api/notice/list"))
                .andExpect(jsonPath("$.data[?(@.id==" + noticeId + ")]").isEmpty());

        // ---- 删除 ----
        mockMvc.perform(delete("/internal/v1/content/banner/" + bannerId).header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0));
        banners.remove(bannerId);
        mockMvc.perform(delete("/internal/v1/content/notice/" + noticeId).header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(0));
        notices.remove(noticeId);
    }

    // ==================== 契约 ====================

    @Test
    @DisplayName("[契约] 管理端分页 JSON 字段名逐字不变（实体字段直接对外）")
    void adminPageFieldsUnchanged() throws Exception {
        long id = createBanner("字段断言_" + suffix);
        mockMvc.perform(get("/internal/v1/content/banner/page").header(TOKEN_HEADER, TOKEN)
                        .param("keyword", "字段断言_" + suffix))
                // PageResult 四个字段
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.pageNum").isNumber())
                .andExpect(jsonPath("$.data.pageSize").isNumber())
                .andExpect(jsonPath("$.data.list").isArray())
                // Banner 实体八个字段（/api/admin/banner/page 现在就返回这个形状）
                .andExpect(jsonPath("$.data.list[0].id").value(id))
                .andExpect(jsonPath("$.data.list[0].title").exists())
                .andExpect(jsonPath("$.data.list[0].imageUrl").exists())
                .andExpect(jsonPath("$.data.list[0].linkUrl").exists())
                .andExpect(jsonPath("$.data.list[0].sort").isNumber())
                .andExpect(jsonPath("$.data.list[0].status").isNumber())
                .andExpect(jsonPath("$.data.list[0].createTime").exists())
                .andExpect(jsonPath("$.data.list[0].updateTime").exists());
    }

    @Test
    @DisplayName("[契约] 校验/不存在 的错误文案逐字不变（400/404）")
    void errorMessagesUnchanged() throws Exception {
        // 缺标题：BannerSaveRequest 上的 @NotBlank 文案
        mockMvc.perform(post("/internal/v1/content/banner").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"http://img/x.jpg\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入轮播标题"));

        // 状态值非法
        long id = createBanner("状态断言_" + suffix);
        mockMvc.perform(put("/internal/v1/content/banner/" + id + "/status").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":9}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("状态值仅支持 0/1"));

        // 不存在
        mockMvc.perform(delete("/internal/v1/content/banner/999999999").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("轮播不存在"));
        mockMvc.perform(delete("/internal/v1/content/notice/999999999").header(TOKEN_HEADER, TOKEN))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("公告不存在"));
    }

    @Test
    @DisplayName("[缓存] 写操作删除共有的首页聚合 key（内容改动前台立即生效）")
    void writeInvalidatesHomeCache() throws Exception {
        assumeTrue(redisUp(), "Redis 未启动，跳过缓存断言");
        setKey(CacheKeys.homeIndex(), "{}", Duration.ofSeconds(60));
        org.junit.jupiter.api.Assertions.assertTrue(hasKey(CacheKeys.homeIndex()));

        createBanner("缓存失效_" + suffix);
        org.junit.jupiter.api.Assertions.assertFalse(hasKey(CacheKeys.homeIndex()),
                "新增轮播后首页聚合缓存应被删除，否则前台最多 45 秒看不到新内容");
    }

    // ==================== helpers ====================

    private long createBanner(String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/internal/v1/content/banner").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"imageUrl\":\"http://img/b.jpg\","
                                + "\"linkUrl\":\"/product/1001\",\"sort\":5}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = readLong(r);
        banners.add(id);
        return id;
    }

    private long createNotice(String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/internal/v1/content/notice").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"content\":\"内容\",\"sort\":9}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long id = readLong(r);
        notices.add(id);
        return id;
    }

    private long countBanners() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM cms_banner", Long.class);
        return n == null ? 0 : n;
    }

    private static long readLong(MvcResult r) throws Exception {
        Number n = JsonPath.read(r.getResponse().getContentAsString(), "$.data");
        return n.longValue();
    }
}
