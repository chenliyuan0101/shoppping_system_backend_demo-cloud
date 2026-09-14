package com.mall.demo.common;

import com.mall.demo.common.client.ContentInternalClient;
import com.mall.demo.support.MySqlTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 文件上传在**单体侧**的契约测试（P2：真正"存"的逻辑已搬去 {@code mall-content}）。
 *
 * <p>这里只守单体这一层该守的东西：
 * <ol>
 *   <li><b>登录态</b>：会员/管理员各自的鉴权仍在这里（内容域没有登录态，也不该有）；</li>
 *   <li><b>前置校验快速失败</b>：空文件、超限文件在单体就挡掉，**不必过网络**；</li>
 *   <li><b>结果透传</b>：内容域返回的 {@code {url,name,size,contentType}} 与错误文案原样返回。</li>
 * </ol>
 *
 * <p>"按文件内容（魔数）识别真实类型""改名成 .png 的 HTML 被拒""真的写进 MinIO 桶"
 * 这些断言属于属主服务，已迁移到 {@code mall-content} 的 {@code InternalUploadMySqlTest}
 * （那里还有一条"内部接口没令牌 → 403"的 fail-closed 断言）。
 */
class UploadMySqlTest extends MySqlTestBase {

    @MockitoBean
    private ContentInternalClient contentClient;

    /** 合法 PNG 文件头（8 字节魔数） */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    @DisplayName("[上传] 未登录会员 → 401（鉴权仍在单体，且不触达内容域）")
    void memberUploadRequiresLogin() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", PNG_MAGIC);
        mockMvc.perform(multipart("/api/upload").file(file))
                .andExpect(jsonPath("$.code").value(401));
        verify(contentClient, never()).upload(any());
    }

    @Test
    @DisplayName("[上传] 空文件 → 400「请选择文件」（本地拦截，不打内容域）")
    void emptyFileRejectedLocally() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/api/admin/upload")
                        .headers(adminHeaders()).file(empty))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请选择文件"));
        verify(contentClient, never()).upload(any());
    }

    @Test
    @DisplayName("[上传] 超过 5MB → 400「图片不能超过 5MB」（本地拦截，不打内容域）")
    void oversizeRejectedLocally() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        mockMvc.perform(multipart("/api/admin/upload")
                        .headers(adminHeaders()).file(file))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("图片不能超过 5MB"));
        verify(contentClient, never()).upload(any());
    }

    @Test
    @DisplayName("[上传] 成功：内容域返回的 url/name/size/contentType 原样透传")
    void uploadResultPassedThrough() throws Exception {
        when(contentClient.upload(any())).thenReturn(Map.of(
                "url", "http://localhost:9000/mall/2026/09/13/x.png",
                "name", "x.png", "size", 8, "contentType", "image/png"));

        MockMultipartFile png = new MockMultipartFile("file", "x.png", "image/png", PNG_MAGIC);
        mockMvc.perform(multipart("/api/admin/upload")
                        .headers(adminHeaders()).file(png))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").value("http://localhost:9000/mall/2026/09/13/x.png"))
                .andExpect(jsonPath("$.data.name").value("x.png"))
                .andExpect(jsonPath("$.data.size").value(8))
                .andExpect(jsonPath("$.data.contentType").value("image/png"));
    }

    @Test
    @DisplayName("[上传][安全] 内容域判定「不是真图片」→ 400 文案原样透传（不降级成 500）")
    void contentSideRejectionPassedThrough() throws Exception {
        when(contentClient.upload(any())).thenThrow(new BusinessException(400,
                "仅支持 jpg/png/webp/gif 图片（按文件内容识别，请勿修改后缀）"));

        // 文件名与 Content-Type 都伪装成 png，实际内容是脚本（真实判定在内容域）
        MockMultipartFile fake = new MockMultipartFile("file", "evil.png", "image/png",
                "<script>alert(1)</script>".getBytes());
        mockMvc.perform(multipart("/api/admin/upload")
                        .headers(adminHeaders()).file(fake))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("按文件内容识别")));
    }

    @Test
    @DisplayName("[上传] 会员登录后可上传（走同一转发链路）")
    void memberUploadAfterLogin() throws Exception {
        String memberToken = registerAs("up_");
        when(contentClient.upload(any())).thenReturn(Map.of(
                "url", "http://localhost:9000/mall/2026/09/13/y.jpg",
                "name", "y.jpg", "size", 4, "contentType", "image/jpeg"));

        MockMultipartFile jpg = new MockMultipartFile("file", "y.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0});
        mockMvc.perform(multipart("/api/upload")
                        .header("Authorization", "Bearer " + memberToken).file(jpg))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").value("http://localhost:9000/mall/2026/09/13/y.jpg"));

        // 本用例自建了会员，按基类约定清理（否则 ums_member 会慢慢堆积测试账号）
        cleanUpBaselines();
    }
}
