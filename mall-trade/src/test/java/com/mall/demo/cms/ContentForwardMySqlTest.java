package com.mall.demo.cms;

import com.mall.demo.common.BusinessException;
import com.mall.demo.common.PageResult;
import com.mall.demo.common.client.ContentInternalClient;
import com.mall.demo.common.dto.AdminBannerVO;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单体侧"薄转发"的契约测试（P2 决策 1）。
 *
 * <p>业务断言已经搬到属主服务（{@code mall-content} 的 {@code InternalContentApiMySqlTest}），
 * 这里守的是**转发这一层自己的正确性**，也就是拆分最容易出问题的三件事：
 * <ol>
 *   <li><b>鉴权仍在单体</b>：没有管理员 token → 401，且**根本不该调用内容域**（否则等于开了一个匿名写入口）；</li>
 *   <li><b>参数原样转发</b>：关键字/状态/分页/请求体都要一模一样地传下去，
 *       少传一个参数就是"悄悄地改变业务"；</li>
 *   <li><b>响应与错误逐字透传</b>：字段名、错误码、错误文案都必须与拆分前一致（C1）——
 *       内容域返回 404「轮播不存在」，单体就必须让客户端看到 404「轮播不存在」，不能变成 500。</li>
 * </ol>
 *
 * <p>用 {@code @MockitoBean} 替掉 {@code ContentInternalClient}：这层测试不依赖内容域进程是否在跑
 * （真实联调由 P2 的端到端验收覆盖）。
 */
class ContentForwardMySqlTest extends MySqlTestBase {

    @MockitoBean
    private ContentInternalClient contentClient;

    // ==================== 鉴权 ====================

    @Test
    @DisplayName("[转发] 无管理员 token → 401，且不触达内容域")
    void rejectsAnonymous() throws Exception {
        mockMvc.perform(post("/api/admin/banner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"imageUrl\":\"http://img/x.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
        verify(contentClient, never()).createBanner(any());
    }

    // ==================== 参数转发 ====================

    @Test
    @DisplayName("[转发] 轮播分页：关键字/状态/分页原样传给内容域，响应字段名不变")
    void bannerPageForwardsParams() throws Exception {
        when(contentClient.bannerPage(eq("促销"), eq(1), eq(2L), eq(20L)))
                .thenReturn(PageResult.of(1, 2, 20, List.of(banner(7L, "促销轮播"))));

        mockMvc.perform(get("/api/admin/banner/page").headers(adminHeaders())
                        .param("keyword", "促销").param("status", "1")
                        .param("pageNum", "2").param("pageSize", "20"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                // 管理端分页的字段形状（改造前直接序列化实体）必须逐字保持
                .andExpect(jsonPath("$.data.list[0].id").value(7))
                .andExpect(jsonPath("$.data.list[0].title").value("促销轮播"))
                .andExpect(jsonPath("$.data.list[0].imageUrl").exists())
                // 未设置的 linkUrl 序列化成 null（Jackson 默认包含 null 字段），字段本身必须还在
                .andExpect(jsonPath("$.data.list[0].linkUrl").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.list[0].sort").isNumber())
                .andExpect(jsonPath("$.data.list[0].status").isNumber())
                .andExpect(jsonPath("$.data.list[0].createTime").exists())
                .andExpect(jsonPath("$.data.list[0].updateTime").exists());
    }

    @Test
    @DisplayName("[转发] 分页不传参时用默认值（pageNum=1, pageSize=10）")
    void pageUsesDefaults() throws Exception {
        when(contentClient.bannerPage(isNull(), isNull(), eq(1L), eq(10L)))
                .thenReturn(PageResult.of(0, 1, 10, List.of()));

        mockMvc.perform(get("/api/admin/banner/page").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("[转发] 新增轮播：请求体逐字段传下去，返回内容域的 id")
    void createBannerForwardsBody() throws Exception {
        when(contentClient.createBanner(any())).thenReturn(1234L);

        mockMvc.perform(post("/api/admin/banner").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"夏季大促\",\"imageUrl\":\"http://img/a.jpg\","
                                + "\"linkUrl\":\"/product/1001\",\"sort\":3,\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(1234));

        ArgumentCaptor<com.mall.demo.common.dto.BannerSaveRequest> captor =
                ArgumentCaptor.forClass(com.mall.demo.common.dto.BannerSaveRequest.class);
        verify(contentClient).createBanner(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("夏季大促");
        assertThat(captor.getValue().getImageUrl()).isEqualTo("http://img/a.jpg");
        assertThat(captor.getValue().getLinkUrl()).isEqualTo("/product/1001");
        assertThat(captor.getValue().getSort()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("[转发] 状态变更/修改/删除：id 与状态值原样传下去")
    void statusUpdateDeleteForward() throws Exception {
mockMvc.perform(put("/api/admin/banner/66/status").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":0}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(contentClient).updateBannerStatus(66L, 0);

        mockMvc.perform(put("/api/admin/banner/66").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"改过的标题\"}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(contentClient).updateBanner(eq(66L), any());

        mockMvc.perform(delete("/api/admin/banner/66").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        verify(contentClient).deleteBanner(66L);

        // 公告同理（两条链路都要覆盖，避免"只改了轮播"）
        mockMvc.perform(put("/api/admin/notice/88/status").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":1}"))
                .andExpect(jsonPath("$.code").value(0));
        verify(contentClient).updateNoticeStatus(88L, 1);
        mockMvc.perform(delete("/api/admin/notice/88").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));
        verify(contentClient).deleteNotice(88L);
    }

    // ==================== 错误透传（C1 最关键的一条） ====================

    @Test
    @DisplayName("[转发] 业务错误逐字透传：404「轮播不存在」/ 400「请输入轮播标题」不能变成 500")
    void businessErrorsPassThrough() throws Exception {
doThrow(new BusinessException(404, "轮播不存在")).when(contentClient).deleteBanner(999L);
        mockMvc.perform(delete("/api/admin/banner/999").headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("轮播不存在"));

        when(contentClient.createNotice(any()))
                .thenThrow(new BusinessException(400, "请输入公告标题"));
        mockMvc.perform(post("/api/admin/notice").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请输入公告标题"));

        doThrow(new BusinessException(400, "状态值仅支持 0/1"))
                .when(contentClient).updateBannerStatus(anyLong(), any());
        mockMvc.perform(put("/api/admin/banner/1/status").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":9}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("状态值仅支持 0/1"));
    }

    @Test
    @DisplayName("[转发] 内容域不可用 → 500「系统繁忙，请稍后重试」（写操作不降级）")
    void downstreamDownReturns500() throws Exception {
        when(contentClient.createBanner(any()))
                .thenThrow(new BusinessException(500, "系统繁忙，请稍后重试"));

        mockMvc.perform(post("/api/admin/banner").headers(adminHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"imageUrl\":\"http://img/x.jpg\"}"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"));
    }

    private static AdminBannerVO banner(long id, String title) {
        AdminBannerVO vo = new AdminBannerVO();
        vo.setId(id);
        vo.setTitle(title);
        vo.setImageUrl("http://img/seed.jpg");
        vo.setSort(1);
        vo.setStatus(1);
        vo.setCreateTime(java.time.LocalDateTime.of(2026, 9, 13, 10, 0, 0));
        vo.setUpdateTime(java.time.LocalDateTime.of(2026, 9, 13, 10, 0, 0));
        return vo;
    }
}
