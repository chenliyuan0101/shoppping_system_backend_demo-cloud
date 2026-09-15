package com.mall.trade.common;

import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.mall.common.support.MemberId;

/**
 * 接口限流测试(@RateLimit，固定窗口计数)。
 *
 * 说明：全局测试配置默认关闭限流(见 src/test/resources/application.properties)，
 * 这里用 @SpringBootTest(properties=...) 内联属性单独开启，并把阈值统一压到 2 便于验证。
 *
 * <p><b>P3-4 / P8-2a：被限流的"载体接口"换过两次，判据一直是限流器本身</b>
 * 原来的 {@code /api/auth/login}（会员登录）随会员域搬去 user-center（P3-4），
 * 当时的替代是单体自己的 {@code /api/admin/auth/login}（scope=admin_login）；
 * 而 P8-2a 把这个端点也删了（{@code /api/admin/auth/**} 已由网关直路由 {@code mall-admin}，
 * 单体侧的登录/服务/实体/mapper 成为死代码）。
 * ⇒ 现在改用**单体仍在服务**的 {@code POST /api/upload}（scope=upload，维度 {@code by=USER}，
 * 见 {@code UploadController}）：限流 key = {@code mall:rl:upload:u{memberId}}，
 * 断言的仍是"固定窗口计数 + 第 N+1 次 429 + 按身份隔离"这三件事，接口换了不影响覆盖。
 *
 * <p>请求体用**空文件**：它在单体就被挡成 400「请选择文件」，不会触达内容域、也不会往 MinIO 写东西
 * ——"计数发生在业务之前"这一点正好靠它证明（前两次 400、第三次 429）。
 */
@SpringBootTest(properties = {
        "mall.rate-limit.enabled=true",
        "mall.rate-limit.limit-override=2"
})
@AutoConfigureMockMvc
class RateLimitMySqlTest extends MySqlTestBase {

    /** 空文件：稳定得到 400「请选择文件」（本地校验），不依赖内容域 */
    private MockMultipartFile emptyFile;

    @BeforeEach
    void requireRedis() {
        assumeTrue(redisUp(), "Redis 未启动，限流降级为放行，跳过用例");
        emptyFile = new MockMultipartFile("file", "a.png", "image/png", new byte[0]);
    }

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    @Test
    @DisplayName("[Redis] 60 秒窗口内超过阈值 → 第 3 次上传返回 429(统一响应体)")
    void uploadRateLimit_exceedsLimit() throws Exception {
        String token = registerAs("rl_");
        // 会员维度计数：新会员 = 新计数桶，用例可重复执行（不依赖清理上一个用例的键）
        redisTemplate.delete("mall:rl:upload:u" + memberId);

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(multipart("/api/upload")
                            .header("Authorization", "Bearer " + token).file(emptyFile))
                    .andExpect(jsonPath("$.code").value(400));   // 空文件被本地挡掉，但计数有效
        }
        mockMvc.perform(multipart("/api/upload")
                        .header("Authorization", "Bearer " + token).file(emptyFile))
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("操作过于频繁，请稍后再试"));

        // 换个会员不受影响(限流按身份隔离)
        String otherToken = registerAs("rl2_");
        mockMvc.perform(multipart("/api/upload")
                        .header("Authorization", "Bearer " + otherToken).file(emptyFile))
                .andExpect(jsonPath("$.code").value(400));
    }
}
