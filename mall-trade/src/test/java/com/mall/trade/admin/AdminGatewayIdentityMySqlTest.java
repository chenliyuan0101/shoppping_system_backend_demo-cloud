package com.mall.trade.admin;

import com.mall.trade.common.JwtUtil;
import com.mall.trade.common.PageResult;
import com.mall.trade.common.TokenVersionService;
import com.mall.trade.common.client.MarketingClient;
import com.mall.trade.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * P8-2a 的核心测试：**单体侧的管理端身份只认网关注入的头**（与 P3-2 的
 * {@code GatewayIdentityMySqlTest} 对称，一个管会员、一个管管理员）。
 *
 * <h2>为什么必须单独有一条这样的套件</h2>
 * 改造前 {@code AdminAuthInterceptor} 走 {@code AdminSession}：自行验签 → 查管理员账号表
 * （存在 / 未禁用）→ 比对令牌版本。P8-2a 把那三段**全部删掉**（那是单体脱离 {@code mall} 库的最后障碍），
 * 身份改由网关给出。这里要钉死的正是"删干净了"这件事——否则很容易出现
 * "看起来只认头，其实还留了一条自行验签的后门"（那等于任何签名有效的令牌都能打后台，
 * 而被禁用/已登出的管理员在单体侧仍然畅通）。
 *
 * <h2>断言清单（每条都对着一句"不许退回去"）</h2>
 * <ol>
 *   <li>头缺失（= 直连端口）⇒ {@code 401 未登录}，且**根本不触达下游**；</li>
 *   <li>凭据不对 ⇒ {@code 401 未登录}，**即使同时带了合法管理员令牌也不回落**（带了伪造凭据就是攻击形状）；</li>
 *   <li>凭据对但 {@code X-Admin-Id} 缺失/不是数字 ⇒ {@code 401 未登录}
 *       （只有 {@code X-Admin-Id} 没有凭据同样不认 —— 那就等于把身份交给客户端）；</li>
 *   <li>三件套齐（生产常态）⇒ {@code code=0}，并按凭据里的 id 走完业务；</li>
 *   <li>🆕 **单体不再自行验签**：合法管理员令牌放在 {@code Authorization} 里、但没有任何身份头 ⇒
 *       {@code 401 未登录}（改造前这一条是 {@code code=0}）；会员令牌同理；</li>
 *   <li>{@code X-Admin-Ver} 缺失仍放行（版本比对在网关，单体只记录）——刻意钉住这个取舍；</li>
 *   <li>注入的 id 会写进 {@code AuthAttribute.ADMIN_USER_ID}（后台"操作人"字段靠它）——
 *       用 {@code /api/admin/refund/{id}/approve} 的 {@code @RequestAttribute} 证明：
 *       不存在的售后单得到 **404**（走到了业务校验）而不是"属性缺失"的 500。</li>
 * </ol>
 *
 * <p>用 {@code @MockitoBean} 替掉 {@code MarketingClient}：这些用例断言的是**鉴权这一层**，
 * 不该依赖营销域进程是否在跑（券转发本身的契约见 {@code AdminCouponMySqlTest}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminGatewayIdentityMySqlTest extends MySqlTestBase {

    private static final String COUPON_PAGE = "/api/admin/coupon/page";

    @MockitoBean
    private MarketingClient marketingClient;

    @AfterEach
    void cleanUp() {
        cleanUpBaselines();
    }

    private void stubEmptyPage() {
        // 后两个参数是**基本类型** long（pageNum/pageSize）⇒ 必须用 anyLong()，any() 会返回 null 并在拆箱时 NPE
        when(marketingClient.adminPage(any(), any(), anyLong(), anyLong()))
                .thenReturn(PageResult.of(0, 1, 10, List.of()));
    }

    // ==================================================================
    // ① 直连端口：没有任何身份头
    // ==================================================================

    @Test
    @DisplayName("[P8-2a] 无身份头（= 直连 8080）→ 401「未登录」，且不转发到下游")
    void withoutIdentityHeaders_is401() throws Exception {
        mockMvc.perform(get(COUPON_PAGE))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    // ==================================================================
    // ② 凭据不对：伪造 X-Gateway-Auth（哪怕同时带了合法令牌也不回落）
    // ==================================================================

    @Test
    @DisplayName("[P8-2a][安全] 伪造凭据 → 401「未登录」，即使同时带了合法管理员令牌也不回落本地验签")
    void forgedCredential_is401_andDoesNotFallBackToToken() throws Exception {
        String validAdminToken = mintAdminToken(ADMIN_ID);

        mockMvc.perform(get(COUPON_PAGE)
                        .header(GW_AUTH_HEADER, "definitely-wrong")
                        .header(GW_ADMIN_ID_HEADER, String.valueOf(ADMIN_ID))
                        .header(GW_ADMIN_VER_HEADER, "0")
                        .header("Authorization", "Bearer " + validAdminToken))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    @Test
    @DisplayName("[P8-2a][安全] 只有 X-Admin-Id（没有共享凭据）→ 401「未登录」")
    void adminIdWithoutCredential_is401() throws Exception {
        mockMvc.perform(get(COUPON_PAGE).header(GW_ADMIN_ID_HEADER, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    // ==================================================================
    // ③ 凭据对但 id 不可用
    // ==================================================================

    @Test
    @DisplayName("[P8-2a][安全] 凭据正确但 X-Admin-Id 缺失/不是数字 → 401「未登录」")
    void missingOrInvalidAdminId_is401() throws Exception {
        mockMvc.perform(get(COUPON_PAGE).header(GW_AUTH_HEADER, GW_SECRET))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        mockMvc.perform(get(COUPON_PAGE)
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_ADMIN_ID_HEADER, "not-a-number"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    // ==================================================================
    // ④ 生产常态：网关注入的三件套
    // ==================================================================

    @Test
    @DisplayName("[P8-2a] 网关注入身份（三件套，**不带 Authorization**）→ code=0，业务照常")
    void gatewayIdentity_isTrusted() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get(COUPON_PAGE).headers(adminHeaders()))
                .andExpect(jsonPath("$.code").value(0));

        verify(marketingClient).adminPage(isNull(), isNull(), eq(1L), eq(10L));
    }

    @Test
    @DisplayName("[P8-2a] X-Admin-Ver 缺失仍放行（版本比对在网关，单体只记录）——刻意钉住这个取舍")
    void missingVerHeader_stillTrusted() throws Exception {
        stubEmptyPage();

        mockMvc.perform(get(COUPON_PAGE)
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_ADMIN_ID_HEADER, String.valueOf(ADMIN_ID)))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ==================================================================
    // ⑤ 单体不再自行验签（P8-2a 的实质）
    // ==================================================================

    @Test
    @DisplayName("[P8-2a] 合法管理员令牌放在 Authorization、但没有网关注入身份 → 401「未登录」（改造前是 code=0）")
    void adminTokenAloneIsNotIdentity() throws Exception {
        String validAdminToken = mintAdminToken(ADMIN_ID);

        mockMvc.perform(get(COUPON_PAGE).header("Authorization", "Bearer " + validAdminToken))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    @Test
    @DisplayName("[P8-2a] 会员令牌放在 Authorization → 401「未登录」（改造前是「请使用管理员账号登录」，那条文案现在归网关）")
    void memberTokenAloneIsNotIdentity() throws Exception {
        String memberToken = registerAs("adminid_");

        mockMvc.perform(get(COUPON_PAGE).header("Authorization", "Bearer " + memberToken))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录"));

        verifyNoInteractions(marketingClient);
    }

    // ==================================================================
    // ⑥ 注入的 id 变成"操作人"
    // ==================================================================

    @Test
    @DisplayName("[P8-2a] 注入的 X-Admin-Id 会写进请求属性：不存在的售后单得到 404（而不是属性缺失的 500）")
    void injectedAdminIdReachesRequestAttribute() throws Exception {
        long ghostRefundId = 999_999_998L;

        mockMvc.perform(post("/api/admin/refund/" + ghostRefundId + "/approve")
                        .header(GW_AUTH_HEADER, GW_SECRET)
                        .header(GW_ADMIN_ID_HEADER, "42")
                        .header(GW_ADMIN_VER_HEADER, "0"))
                .andExpect(jsonPath("$.code").value(404));
    }

    // ==================== helpers ====================

    /**
     * 现签一个真实的管理员令牌（版本号取 Redis 当前值，claims 口径与 {@code mall-admin} 登录签发一致）。
     *
     * <p>用途只有一个：证明**单体已经不看它了**（用例 ⑤）。本套件其余用例一律用身份头。
     */
    private String mintAdminToken(long adminId) {
        return jwtUtil.createToken(adminId, "admin", JwtUtil.TYPE_ADMIN,
                tokenVersionService.current(TokenVersionService.TYPE_ADMIN, adminId));
    }
}
