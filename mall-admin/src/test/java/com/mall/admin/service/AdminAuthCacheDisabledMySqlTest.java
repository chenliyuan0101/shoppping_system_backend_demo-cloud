package com.mall.admin.service;

import com.mall.admin.support.CacheKeys;
import com.mall.admin.support.TokenVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * **fail-open 专项**（P7 §2.5 / 任务书明写）：缓存不可用时**绝不能**让登录坏掉。
 *
 * <p>用 {@code mall.cache.enabled=false} 把 {@code CacheService} 整体关成 no-op
 * （等价于"Redis 不可用"——连的是真库，只是所有 Redis 行为被跳过）。
 * 这是唯一能在**不启停 Redis 进程**的前提下（本批硬约束：不许启停任何进程）验证降级路径的做法。
 *
 * <p>注意本类**不继承** {@code AdminTestBase}：它的 {@code @SpringBootTest(properties=...)}
 * 会覆盖掉这里的属性（父子类各自的 @SpringBootTest 属性不合并），因此这里自带一份配置。
 */
@SpringBootTest(properties = {
        "mall.cache.enabled=false",                       // 🔴 本用例的主题：缓存整体不可用
        "mall.gateway.auth-token=test-gateway-token",
        "mall.admin.status-reconcile-enabled=false"
})
@AutoConfigureMockMvc
class AdminAuthCacheDisabledMySqlTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AdminStatusService adminStatusService;

    private long seedAdminId() {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = 'admin'", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    @Test
    @DisplayName("🔴 缓存不可用（mall.cache.enabled=false）时登录**照常成功**，只是不写状态缓存")
    void login_stillWorks_whenCacheUnavailable() throws Exception {
        String statusKey = CacheKeys.adminStatus(seedAdminId());
        String verKey = CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, seedAdminId());
        String verBackup = redisTemplate.opsForValue().get(verKey);
        try {
            redisTemplate.delete(statusKey);

            mockMvc.perform(post("/api/admin/auth/login")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.token").isNotEmpty());

            // fail-open 的直接证据：状态缓存根本没被写（而不是"写了一半"）
            assertThat(redisTemplate.opsForValue().get(statusKey)).isNull();
        } finally {
            if (verBackup == null) {
                redisTemplate.delete(verKey);
            } else {
                redisTemplate.opsForValue().set(verKey, verBackup);
            }
            redisTemplate.delete(statusKey);
        }
    }

    @Test
    @DisplayName("缓存不可用时对账任务**不抛异常**（空转即可，下一轮再补）")
    void reconcile_doesNotThrow_whenCacheUnavailable() {
        assertThatCode(() -> adminStatusService.reconcile()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缓存不可用时 me/logout 的身份校验不受影响（令牌版本比对按 fail-open 放行）")
    void me_worksWhenCacheUnavailable() throws Exception {
        String verKey = CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, seedAdminId());
        String verBackup = redisTemplate.opsForValue().get(verKey);
        try {
            String token = com.jayway.jsonpath.JsonPath.read(
                    mockMvc.perform(post("/api/admin/auth/login")
                                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                    .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                            .andReturn().getResponse().getContentAsString(), "$.data.token");

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/admin/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.username").value("admin"));
        } finally {
            if (verBackup == null) {
                redisTemplate.delete(verKey);
            } else {
                redisTemplate.opsForValue().set(verKey, verBackup);
            }
            redisTemplate.delete(CacheKeys.adminStatus(seedAdminId()));
        }
    }
}
