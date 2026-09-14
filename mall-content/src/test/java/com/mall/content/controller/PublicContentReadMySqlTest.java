package com.mall.content.controller;

import com.mall.content.support.ContentTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公开读路径的真库测试（P2 从单体的 {@code HomeMySqlTest} 迁过来的那部分）。
 *
 * <p>覆盖两件事：
 * <ol>
 *   <li><b>数据来自本服务的库</b>（{@code mall_content.cms_*}）：改造前是 {@code mall.cms_*}，
 *       搬迁是否成功、数据源是否真的切过去了，就靠这里"读得到 seed 基线"来证明；</li>
 *   <li><b>只返回启用项</b>：停用的轮播/公告不许出现在前台（改造前就有的口径，拆服务时最容易被漏掉）。</li>
 * </ol>
 *
 * <p>首页聚合里"商品区块"的部分不在这里测（那是跨服务调用 + 降级，见
 * {@code HomeAggregationMySqlTest}）；这里只断言内容那部分与列表接口。
 */
class PublicContentReadMySqlTest extends ContentTestBase {

    @Test
    @DisplayName("[MySQL] /api/banner/list 与 /api/notice/list 仅返回启用数据")
    void lists_excludeDisabled() throws Exception {
        // 插入一条停用轮播与一条停用公告，它们不应出现在列表里
        jdbcTemplate.update("INSERT INTO cms_banner (title, image_url, link_url, sort, status, create_time, update_time) "
                + "VALUES ('停用banner_" + suffix + "', 'http://img/disabled.jpg', null, 99, 0, NOW(), NOW())");
        jdbcTemplate.update("INSERT INTO cms_notice (title, content, sort, status, publish_time) "
                + "VALUES ('停用公告_" + suffix + "', 'x', 99, 0, NOW())");

        try {
            mockMvc.perform(get("/api/banner/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    // seed 基线：搬迁前的库里至少 2 条启用轮播
                    .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                    .andExpect(jsonPath("$.data[?(@.title=='停用banner_" + suffix + "')]").isEmpty())
                    // 前台字段形状（BannerVO）
                    .andExpect(jsonPath("$.data[0].id").isNumber())
                    .andExpect(jsonPath("$.data[0].imageUrl").isNotEmpty());

            mockMvc.perform(get("/api/notice/list"))
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.data[?(@.title=='停用公告_" + suffix + "')]").isEmpty())
                    .andExpect(jsonPath("$.data[0].title").isNotEmpty())
                    .andExpect(jsonPath("$.data[0].createTime").exists());
        } finally {
            jdbcTemplate.update("DELETE FROM cms_banner WHERE title = ?", "停用banner_" + suffix);
            jdbcTemplate.update("DELETE FROM cms_notice WHERE title = ?", "停用公告_" + suffix);
        }
    }
}
