package com.mall.admin.service;

import com.jayway.jsonpath.JsonPath;
import com.mall.admin.support.AdminTestBase;
import com.mall.admin.support.BusinessException;
import com.mall.admin.support.CacheKeys;
import com.mall.admin.support.TokenVersionService;
import com.mall.admin.support.dto.AdminStatusVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * P7 §2.5 的核心交付：**管理端状态缓存写入方**的真库 + 真 Redis 测试。
 *
 * <p>网关（{@code AdminIdentityFilter}）只读两个 key，写入方只有本服务。本套件把那件事钉成可执行断言：
 * <pre>
 *   key ①  mall:cache:admin:status:{id}  值 {"adminId":N,"username":"…","status":0|1}（也接受裸 "0"/"1"）
 *         网关语义：可解析且 status != 1 ⇒ 403 账号已被禁用；键不存在/不可解析 ⇒ fail-open 放行
 *   key ②  mall:token:ver:admin:{id}     十进制字符串（INCR，TTL 30 天）
 *         网关语义：与 token 的 ver 不一致 ⇒ 401 登录已失效，请重新登录
 * </pre>
 *
 * <h2>为什么"禁用写 0 而不是删键"是硬要求</h2>
 * 会员侧是先例（禁用只删不写），因为会员的 401 由下游服务查库产出；
 * 管理端相反：{@code 403 账号已被禁用} **只能**由网关产出，而网关**只有这一份信息**。
 * 删键 = 网关 fail-open = 403 永远出不来。用例 {@code reconcile_cacheMissingAndDbDisabled_*} 就是这条。
 *
 * <h2>对账任务为什么必须存在（本套件的第二个主题）</h2>
 * 今天**没有"禁用管理员"的入口**（禁用是直接改 {@code sys_user}），
 * 所以用例全部走"**直接改库** → 调 {@code reconcile()} → 断言缓存与版本号"这条真实路径。
 */
class AdminStatusCacheMySqlTest extends AdminTestBase {

    /** 与网关 {@code AdminIdentityFilter.STATUS_FIELD} 同形的正则（契约测试：证明写入的值网关读得出来） */
    private static final Pattern GATEWAY_STATUS_FIELD = Pattern.compile("\"status\"\\s*:\\s*\"?(\\d+)\"?");

    /** 与网关 {@code AdminIdentityFilter.BARE_NUMBER} 同形的正则 */
    private static final Pattern GATEWAY_BARE_NUMBER = Pattern.compile("-?\\d+");

    private static final String KEY_ADMIN_STATUS_PREFIX = "mall:cache:admin:status:";
    private static final String KEY_TOKEN_VER_PREFIX = "mall:token:ver:admin:";

    /** 用网关的解析规则取出状态；取不到返回 null（= 网关会 fail-open 放行） */
    private static Long gatewayReadStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        Matcher field = GATEWAY_STATUS_FIELD.matcher(value);
        if (field.find()) {
            return Long.parseLong(field.group(1));
        }
        if (GATEWAY_BARE_NUMBER.matcher(value).matches()) {
            return Long.parseLong(value);
        }
        return null;
    }

    // ==================== key 名与值形状（跨服务契约，字面量断言） ====================

    @Test
    @DisplayName("[契约] 两个 key 名逐字：mall:cache:admin:status:{id} 与 mall:token:ver:admin:{id}")
    void cacheKeys_matchGatewayContractLiterally() {
        assertThat(CacheKeys.adminStatus(1)).isEqualTo("mall:cache:admin:status:1");
        assertThat(CacheKeys.adminStatus(9)).isEqualTo("mall:cache:admin:status:9");
        assertThat(CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, 1)).isEqualTo("mall:token:ver:admin:1");
        assertThat(CacheKeys.tokenVersion(TokenVersionService.TYPE_ADMIN, 9)).isEqualTo("mall:token:ver:admin:9");
    }

    @Test
    @DisplayName("[契约] 登录成功写出 status=1，值形状 = {\"adminId\":..,\"username\":..,\"status\":1}，网关解析得 1")
    void login_writesEnabledStatusCache_matchingGatewayContract() throws Exception {
        assumeTrue(redisUp(), "Redis 不可用，跳过缓存契约断言（fail-open 是正确行为，另见 AdminAuthCacheDisabledMySqlTest）");
        long id = seedAdminId();

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(jsonPath("$.code").value(0));

        String raw = rawValue(KEY_ADMIN_STATUS_PREFIX + id);
        assertThat(raw).as("登录成功必须写状态缓存（网关读它判 403）").isNotNull();
        assertThat(raw).isEqualTo("{\"adminId\":" + id + ",\"username\":\"admin\",\"status\":1}");

        // 网关的解析规则必须能从这份值里读出 status=1（否则网关会 fail-open，等于白写）
        assertThat(gatewayReadStatus(raw)).isEqualTo(1L);

        Map<String, Object> parsed = JsonPath.read(raw, "$");
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("adminId", "username", "status");

        // TTL 照会员侧先例：10 分钟。CacheService 会加 ±15% 抖动（防雪崩），
        // 因此断言区间而不是精确值：600s * 1.15 = 690s 是上限。
        Long ttl = ttlSeconds(KEY_ADMIN_STATUS_PREFIX + id);
        assertThat(ttl).isNotNull().isBetween(1L, 690L);
    }

    @Test
    @DisplayName("[契约] 写入/删除：put/putDisabled/evict 直接作用于网关那个键")
    void cacheWriter_putAndEvict() {
        assumeTrue(redisUp(), "Redis 不可用，跳过缓存写入断言");
        long id = insertAdmin("p7adm_cache_put_", 1);
        String username = usernameOf(id);
        String key = KEY_ADMIN_STATUS_PREFIX + id;

        adminStatusCache.putEnabled(id, username);
        assertThat(rawValue(key)).isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":1}");

        adminStatusCache.putDisabled(id, username);
        assertThat(rawValue(key)).isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":0}");
        assertThat(gatewayReadStatus(rawValue(key))).isEqualTo(0L);   // ⇒ 网关会回 403

        AdminStatusVO vo = adminStatusCache.get(id);
        assertThat(vo).isNotNull();
        assertThat(vo.adminId()).isEqualTo(id);
        assertThat(vo.status()).isZero();

        adminStatusCache.evict(id);
        assertThat(rawValue(key)).isNull();
        // 键不存在 ⇒ 网关 fail-open（这就是"不能用删键来表达禁用"的原因）
        assertThat(gatewayReadStatus(rawValue(key))).isNull();
    }

    // ==================== 改状态（未来"禁用管理员"端点的落点） ====================

    @Test
    @DisplayName("启用→禁用：落库 + 写缓存(0) + bump 令牌版本号一次（双保险）")
    void updateStatus_disabled_writesZeroAndBumpsVersion() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_status_off_", 1);
        String username = usernameOf(id);
        long before = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        adminStatusService.updateStatus(id, 0);

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, id))
                .isZero();
        // 写 0（不是删）：网关要靠它产出 403
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":0}");
        // 双保险：版本号 +1 ⇒ 旧令牌在网关直接 401
        assertThat(rawValue(KEY_TOKEN_VER_PREFIX + id)).isEqualTo(String.valueOf(before + 1));
    }

    @Test
    @DisplayName("启用→启用：写缓存(1) 且**不** bump 版本号（否则每次改状态都会把管理员踢下线）")
    void updateStatus_enabled_writesOneAndDoesNotBump() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_status_on_", 1);
        String username = usernameOf(id);
        long before = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        adminStatusService.updateStatus(id, 1);

        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":1}");
        assertThat(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id)).isEqualTo(before);
    }

    @Test
    @DisplayName("禁用→启用（重新启用）：缓存写回 1，版本号不变")
    void updateStatus_reenable_writesOne() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_status_re_", 1);
        String username = usernameOf(id);

        adminStatusService.updateStatus(id, 0);
        long afterDisable = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        adminStatusService.updateStatus(id, 1);
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":1}");
        assertThat(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id)).isEqualTo(afterDisable);
    }

    @Test
    @DisplayName("非法状态值 → 400「状态值仅支持 0禁用 1正常」")
    void updateStatus_invalidValue_400() {
        long id = insertAdmin("p7adm_status_bad_", 1);

        assertThatThrownBy(() -> adminStatusService.updateStatus(id, 7))
                .isInstanceOf(BusinessException.class)
                .hasMessage("状态值仅支持 0禁用 1正常")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
        assertThatThrownBy(() -> adminStatusService.updateStatus(id, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("状态值仅支持 0禁用 1正常");
    }

    @Test
    @DisplayName("管理员不存在 → 404「管理员不存在」，并清掉可能残留的状态缓存")
    void updateStatus_unknownAdmin_404_andEvictsCache() {
        long ghost = 99_999_999L;
        redisTemplate.opsForValue().set(KEY_ADMIN_STATUS_PREFIX + ghost, "{\"adminId\":" + ghost
                + ",\"username\":\"ghost\",\"status\":0}");
        try {
            assertThatThrownBy(() -> adminStatusService.updateStatus(ghost, 0))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("管理员不存在")
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(404));
            assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + ghost)).isNull();
        } finally {
            redisTemplate.delete(KEY_ADMIN_STATUS_PREFIX + ghost);
        }
    }

    // ==================== 定时对账（P7 §2.5 的"禁用能生效"的唯一机制） ====================

    @Test
    @DisplayName("🔴 对账：启用→禁用跃迁（缓存还写着 1，库里已改 0）→ 写禁用值 + bump 版本号；第二轮收敛不再 bump")
    void reconcile_enabledToDisabledTransition_writesDisabledAndBumpsOnce_thenConverges() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        // 这是今天真实发生的事：缓存里是登录时写的 1，而值班人员**直接改库**把 status 置 0
        long id = insertAdmin("p7adm_recon_tr_", 1);
        String username = usernameOf(id);
        adminStatusCache.putEnabled(id, username);
        long versionBefore = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);

        setStatusInDb(id, 0);   // 直接改库（今天唯一的禁用方式）

        // 对账前：缓存仍然说"启用" ⇒ 网关会放行（这正是 §2.5 要堵的洞）
        assertThat(gatewayReadStatus(rawValue(KEY_ADMIN_STATUS_PREFIX + id))).isEqualTo(1L);

        int fixed = adminStatusService.reconcile();

        assertThat(fixed).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM sys_user WHERE id = ?", Integer.class, id))
                .isZero();
        // ① 缓存纠正成"禁用"（网关据此回 403 账号已被禁用）
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":0}");
        assertThat(gatewayReadStatus(rawValue(KEY_ADMIN_STATUS_PREFIX + id))).isZero();
        // ② 版本号 bump（双保险：即使缓存被清/写错，旧令牌也在网关过不去）
        assertThat(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id)).isEqualTo(versionBefore + 1);

        // 收敛：再跑一轮不应再 bump（否则每 10 秒就把版本号推高一次）
        assertThat(adminStatusService.reconcile()).isZero();
        assertThat(tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id)).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("🔴 对账：库里是禁用、缓存**键不存在** → 补写禁用值 + bump（补上「缓存缺失 ⇒ 网关 fail-open」的窗口）")
    void reconcile_cacheMissingAndDbDisabled_writesDisabledAndBumps() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_recon_miss_", 0);   // 建号即禁用，且从没有过缓存
        String username = usernameOf(id);
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id)).as("前提：缓存不存在（网关 fail-open）").isNull();

        int fixed = adminStatusService.reconcile();

        assertThat(fixed).isGreaterThanOrEqualTo(1);
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":0}");
        assertThat(rawValue(KEY_TOKEN_VER_PREFIX + id)).as("禁用时对账必须 bump 版本号（从 0 到 1）").isEqualTo("1");
    }

    @Test
    @DisplayName("对账：启用状态补写缓存但**不** bump 版本号")
    void reconcile_dbEnabled_writesEnabledAndDoesNotBump() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_recon_on_", 1);
        String username = usernameOf(id);

        adminStatusService.reconcile();

        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id))
                .isEqualTo("{\"adminId\":" + id + ",\"username\":\"" + username + "\",\"status\":1}");
        assertThat(rawValue(KEY_TOKEN_VER_PREFIX + id)).as("启用不该动版本号").isNull();
    }

    @Test
    @DisplayName("对账：全部一致时返回 0（幂等/收敛），且不触碰任何 key")
    void reconcile_isIdempotentWhenNothingChanged() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_recon_idem_", 0);

        assertThat(adminStatusService.reconcile()).isGreaterThanOrEqualTo(1);
        String statusRaw = rawValue(KEY_ADMIN_STATUS_PREFIX + id);
        String verRaw = rawValue(KEY_TOKEN_VER_PREFIX + id);

        assertThat(adminStatusService.reconcile()).isZero();
        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id)).isEqualTo(statusRaw);
        assertThat(rawValue(KEY_TOKEN_VER_PREFIX + id)).isEqualTo(verRaw);
    }

    @Test
    @DisplayName("[契约] 写入的 JSON 同时满足网关接受的两种写法之一（JSON 字段 / 裸数字），且 status 语义 0=禁用 1=正常")
    void writtenValue_isParsableByGatewayRules() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_gw_parse_", 1);
        String username = usernameOf(id);

        adminStatusCache.putDisabled(id, username);
        assertThat(gatewayReadStatus(rawValue(KEY_ADMIN_STATUS_PREFIX + id))).isEqualTo(0L);

        adminStatusCache.putEnabled(id, username);
        assertThat(gatewayReadStatus(rawValue(KEY_ADMIN_STATUS_PREFIX + id))).isEqualTo(1L);

        // 网关也接受裸数字：本服务的写法不必是它，但两者语义必须一致（这里顺带证明解析规则是宽松的那一侧）
        assertThat(gatewayReadStatus("0")).isZero();
        assertThat(gatewayReadStatus("1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("状态未知（status=null）时**不写**缓存（写一份取不到 status 的 JSON 会让 403 静默失效）")
    void putWithUnknownStatus_skipsWrite() {
        assumeTrue(redisUp(), "Redis 不可用，跳过");
        long id = insertAdmin("p7adm_cache_null_", 1);

        adminStatusCache.put(id, usernameOf(id), null);

        assertThat(rawValue(KEY_ADMIN_STATUS_PREFIX + id)).isNull();
    }

    @Test
    @DisplayName("登录返回的令牌 ver 与 Redis 里的版本号一致（网关比对的前提）")
    void loginTokenVersionMatchesRedis() throws Exception {
        long id = seedAdminId();
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andReturn();
        String token = JsonPath.read(body(result), "$.data.token");

        long redisVer = tokenVersionService.current(TokenVersionService.TYPE_ADMIN, id);
        assertThat(jwtUtil.parse(token).ver()).isEqualTo(redisVer);
    }
}
