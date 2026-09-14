package com.mall.admin.support;

import com.mall.admin.service.AdminAuthService;
import com.mall.admin.service.AdminStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * mall-admin 的真库测试基类（与 user-center 的 {@code UserCenterTestBase} 同一套思路）。
 *
 * <p>⚠️ 网关身份凭据必须写在 {@code @SpringBootTest(properties=...)} 上：profile 专属配置
 * （{@code application-dev.yaml}）优先级高于 {@code src/test/resources/application.properties}，
 * 写进 properties 文件会被 dev 凭据盖掉，而"凭据不对 → 401"的用例照样通过、把问题掩盖（P1/P2/P3 实测踩过）。
 *
 * <p>本基类给的是**真库（mall_admin）+ 真 Redis + MockMvc**：管理端这批的核心判据
 * （登录态文案、状态缓存的键和值、令牌版本号）都必须落在真实的行与真实的键上，
 * 否则"缓存是写给网关看的"这件事只停留在注释里。
 */
@SpringBootTest(properties = {
        "mall.gateway.auth-token=test-gateway-token",
        "mall.admin.status-reconcile-enabled=false"
})
@AutoConfigureMockMvc
public abstract class AdminTestBase {

    /** 网关注入的三个身份头（值必须与上面注入的 {@code mall.gateway.auth-token} 对应） */
    protected static final String GW_AUTH_HEADER = "X-Gateway-Auth";
    protected static final String GW_ADMIN_ID_HEADER = "X-Admin-Id";
    protected static final String GW_ADMIN_VER_HEADER = "X-Admin-Ver";
    protected static final String GW_SECRET = "test-gateway-token";

    /** 迁移过来的真 seed 管理员（登录口令与单体/《数据库建库文档》一致） */
    protected static final String SEED_ADMIN_USERNAME = "admin";
    protected static final String SEED_ADMIN_PASSWORD = "admin123";

    /** 用例里新建的测试管理员的口令 */
    protected static final String TEST_ADMIN_PASSWORD = "Adm123456";

    @Autowired
    protected MockMvc mockMvc;

    /** 指向本服务自己的库（mall_admin）——"表真的搬过来了"就靠它断言 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JwtUtil jwtUtil;

    @Autowired
    protected TokenVersionService tokenVersionService;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired
    protected AdminStatusCache adminStatusCache;

    @Autowired
    protected AdminStatusService adminStatusService;

    @Autowired
    protected AdminAuthService adminAuthService;

    @Autowired
    protected AdminSession adminSession;

    /** 测试上下文的真实签名密钥（取自 profile 的 {@code mall.jwt.secret}）——用来手搓"过期/异密钥"令牌 */
    @org.springframework.beans.factory.annotation.Value("${mall.jwt.secret}")
    protected String jwtSecret;

    // ==================== 公共断言/取数 helper ====================

    protected String schemaName() {
        return jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
    }

    protected String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    protected String rawValue(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    protected Long ttlSeconds(String key) {
        return redisTemplate.getExpire(key);
    }

    /** Redis 是否可用（不可用时按 assumption 跳过"必须真读到键"的断言） */
    protected boolean redisUp() {
        try {
            String probe = "mall:test:ping";
            redisTemplate.opsForValue().set(probe, "pong", Duration.ofSeconds(10));
            boolean ok = "pong".equals(redisTemplate.opsForValue().get(probe));
            redisTemplate.delete(probe);
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 给请求装上"网关注入的身份头"（{@code X-Gateway-Auth} + {@code X-Admin-Id} + {@code X-Admin-Ver}）。
     *
     * <p>这三个头**就是**生产链路里网关验签后注入的东西，因此测试不需要真的起网关，
     * 也不需要 JWT：身份链路的断言只依赖本方法的三个头。
     * 反过来，不调本方法就等于"客户端直连"（走过渡态的本地验签）—— 这正是双路设计的可执行证据。
     */
    protected MockHttpServletRequestBuilder asGatewayAdmin(MockHttpServletRequestBuilder builder,
                                                          long adminId, long ver) {
        return builder.header(GW_AUTH_HEADER, GW_SECRET)
                .header(GW_ADMIN_ID_HEADER, String.valueOf(adminId))
                .header(GW_ADMIN_VER_HEADER, String.valueOf(ver));
    }

    /** 后台登录（真 seed 管理员 admin/admin123），返回裸 token */
    protected String loginSeedAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + SEED_ADMIN_USERNAME + "\",\"password\":\""
                                + SEED_ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(body(result), "$.data.token");
    }

    /** 现签一个 {@code typ=admin} 的令牌（版本号取 Redis 当前值，语义与登录签发一致） */
    protected String signAdminToken(long adminId, String username, String type) {
        return jwtUtil.createToken(adminId, username, type,
                tokenVersionService.current(TokenVersionService.TYPE_ADMIN, adminId));
    }

    /**
     * 真库插入一个测试管理员（用户名带随机后缀，避免撞 {@code uk_username}），返回其 id。
     *
     * <p>密码用本进程的 {@link PasswordEncoder} 现算 BCrypt —— 与生产入库口径相同，
     * 因此"登录时 BCrypt 能验过"这件事是真在库上发生的。
     */
    protected long insertAdmin(String usernamePrefix, int status) {
        String username = usernamePrefix + (System.nanoTime() % 100_000_000L);
        jdbcTemplate.update("""
                INSERT INTO sys_user (username, password, nickname, status)
                VALUES (?, ?, ?, ?)
                """, username, passwordEncoder.encode(TEST_ADMIN_PASSWORD), "集成测试管理员", status);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
        if (id == null) {
            throw new IllegalStateException("插入管理员失败: " + username);
        }
        createdAdminIds.add(id);
        createdAdminUsernames.add(username);
        return id;
    }

    private final java.util.List<Long> createdAdminIds = new java.util.ArrayList<>();
    private final java.util.List<String> createdAdminUsernames = new java.util.ArrayList<>();

    protected String usernameOf(long adminId) {
        return jdbcTemplate.queryForObject("SELECT username FROM sys_user WHERE id = ?", String.class, adminId);
    }

    /**
     * 物理删除测试管理员及其两个 Redis key（可重复执行；不留残余以免污染其它套件）。
     *
     * <p>⚠️ 只删**用例自己造的**行：seed 行（admin）与它的 Redis key 由调用方按需恢复
     * （见 {@code AdminStatusCacheMySqlTest} 的版本号基线恢复）。
     */
    protected void deleteAdmins() {
        for (Long id : createdAdminIds) {
            redisTemplate.delete(CacheKeys.adminStatus(id));
            redisTemplate.delete(CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, id));
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", id);
        }
        createdAdminIds.clear();
        createdAdminUsernames.clear();
    }

    /** 直接改库把管理员禁用（模拟"今天唯一存在的禁用方式"：值班人员手工 UPDATE） */
    protected void setStatusInDb(long adminId, int status) {
        jdbcTemplate.update("UPDATE sys_user SET status = ? WHERE id = ?", status, adminId);
    }

    // ==================== seed 管理员（迁移过来的那 1 行）与自清理 ====================

    /** 迁移过来的 seed 管理员 id（用例假设它存在；不存在说明 02 迁移脚本没跑） */
    protected long seedAdminId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Long.class, SEED_ADMIN_USERNAME);
        if (id == null) {
            throw new IllegalStateException("mall_admin.sys_user 里没有 seed 管理员 admin —— 请先执行 db/02 迁移脚本");
        }
        return id;
    }

    protected String seedNickname() {
        return jdbcTemplate.queryForObject("SELECT nickname FROM sys_user WHERE username = ?",
                String.class, SEED_ADMIN_USERNAME);
    }

    /** 本套件开始前 seed 管理员的令牌版本号（用例会 bump 它，跑完恢复，避免影响其它套件） */
    private String seedVersionBackup;

    @org.junit.jupiter.api.BeforeEach
    void backupSeedAdminState() {
        seedVersionBackup = rawValue(CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, seedAdminId()));
    }

    /**
     * 自清理（可重复执行，不留残余）：
     * 恢复 seed 管理员的令牌版本号、删掉 seed 的状态缓存（那是本批新增的键，跑完不该留下），
     * 并物理删除用例自己造的管理员行及其两个 key。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreSeedAdminStateAndCleanup() {
        long id = seedAdminId();
        String key = CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, id);
        if (seedVersionBackup == null) {
            redisTemplate.delete(key);
        } else {
            redisTemplate.opsForValue().set(key, seedVersionBackup);
        }
        redisTemplate.delete(CacheKeys.adminStatus(id));
        deleteAdmins();
    }

    // ==================== 手搓令牌（只为构造"畸形/过期/异密钥"这些生产里不会自然出现的形状） ====================

    /**
     * 用给定密钥对任意 payload 做 HS256 签名，产出与 {@code JwtUtil.createToken} 同构的三段式令牌。
     *
     * <p>为什么要自己签：{@code JwtUtil} 只会签"当前时刻 + 有效期之后"的令牌，
     * 而 C1 必须覆盖的三个形状（**过期**、**异密钥**、**typ 不对**）里有两个造不出来。
     * 签名方式与生产逐字相同（base64url 无填充 + HmacSHA256 + {@code MessageDigest} 无关的普通比较即可，
     * 因为这里只负责生成、校验仍走生产代码）。
     */
    protected String signRawToken(String secret, String payloadJson) {
        try {
            String header = b64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String payload = b64Url(payloadJson);
            String signingInput = header + "." + payload;
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return signingInput + "." + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("测试令牌签名失败", e);
        }
    }

    private static String b64Url(String text) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 造一个**已过期**的管理端令牌（exp 早于当前时刻 60 秒；单体口径 {@code exp < now} 即失效） */
    protected String expiredAdminToken(long adminId, String username) {
        long now = java.time.Instant.now().getEpochSecond();
        return signRawToken(jwtSecret, "{\"sub\":" + adminId + ",\"name\":\"" + username + "\",\"typ\":\"admin\","
                + "\"ver\":0,\"iat\":" + (now - 7200) + ",\"exp\":" + (now - 60) + "}");
    }
}
