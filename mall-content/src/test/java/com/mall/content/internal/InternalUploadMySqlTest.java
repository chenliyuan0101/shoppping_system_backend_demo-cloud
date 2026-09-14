package com.mall.content.internal;

import com.mall.content.support.ContentTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 文件上传在**属主服务**这一侧的测试（P2：MinIO 与"真实类型判定"都在这里）。
 *
 * <p>它承接了原先单体 {@code UploadMySqlTest} 里属于内容域的那些断言，
 * 并按"内部接口"的形态补上了鉴权：
 * <ol>
 *   <li><b>fail-closed</b>：没有 {@code X-Internal-Token} → 403（这个端点能往公开桶里写东西，
 *       一旦漏配就是免费图床）；</li>
 *   <li><b>按文件内容（魔数）判定</b>：改名成 {@code .png} 的 HTML → 400，文案逐字保持；</li>
 *   <li><b>真的写进 MinIO 桶</b>：真 PNG 才能拿到 {@code http://.../mall/...} 的 URL
 *       （MinIO 没起时跳过，负向用例永远执行）。</li>
 * </ol>
 */
class InternalUploadMySqlTest extends ContentTestBase {

    /** 合法 PNG 文件头（8 字节魔数） */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    @DisplayName("[内部接口] 上传没带令牌 → 403（不能成为公开图床）")
    void rejectsWithoutToken() throws Exception {
        MockMultipartFile png = new MockMultipartFile("file", "a.png", "image/png", PNG_MAGIC);
        mockMvc.perform(multipart("/internal/v1/content/upload").file(png))
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("[内部接口][安全] 只改后缀/声明头部的假图片 → 400（按文件内容识别，文案逐字不变）")
    void fakeImage_isRejected() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "evil.png", "image/png",
                "<script>alert(1)</script>".getBytes());
        mockMvc.perform(multipart("/internal/v1/content/upload").header(TOKEN_HEADER, TOKEN).file(fake))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("按文件内容识别")));
    }

    @Test
    @DisplayName("[内部接口] 空文件 → 400「请选择文件」")
    void emptyFile_isRejected() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
        mockMvc.perform(multipart("/internal/v1/content/upload").header(TOKEN_HEADER, TOKEN).file(empty))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请选择文件"));
    }

    @Test
    @DisplayName("[MinIO] 真 PNG → 上传成功并返回桶内 URL")
    void uploadToMinio() throws Exception {
        assumeTrue(minioReachable(), "MinIO(9000)未启动，跳过真实上传");
        MockMultipartFile png = new MockMultipartFile("file", "test.png", "image/png", PNG_MAGIC);
        mockMvc.perform(multipart("/internal/v1/content/upload").header(TOKEN_HEADER, TOKEN).file(png))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.url").value(org.hamcrest.Matchers.containsString(":9000/mall/")))
                .andExpect(jsonPath("$.data.contentType").value("image/png"));
    }

    private boolean minioReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9000), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
