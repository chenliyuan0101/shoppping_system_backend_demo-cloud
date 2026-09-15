package com.mall.trade.auth.support;

import com.mall.trade.common.BusinessException;
import com.mall.trade.common.JwtUtil;
import com.mall.trade.common.MemberStatusCache;
import com.mall.trade.common.TokenVersionService;
import com.mall.trade.common.contract.MemberQueryService;
import com.mall.trade.common.dto.MemberSnapshotVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MemberSession 单测(Mockito，无需数据库/Redis)：校验口径 + **同一请求只解析一次**的缓存行为。
 *
 * 背景：限流切面(解析限流身份)与控制器(取会员 id)会在同一请求里各调用一次，
 * 若每次都验签+取会员档案+读令牌版本号，就是一次无意义的重复开销——本测试锁住"只解析一次"。
 *
 * <p>P3-4 起成员档案来自域契约 {@link MemberQueryService#snapshot(Long)}
 * （生产=user-center 内部接口），不再是本地 {@code MemberMapper.selectById}：
 * 这里用 mock 钉住的是**回退路径的校验顺序与缓存语义**（改造前的老逻辑，接口方法一字未改）。
 */
@ExtendWith(MockitoExtension.class)
class MemberSessionTest {

    private static final String TOKEN = "tok-abc";
    private static final String HEADER = "Bearer " + TOKEN;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private MemberQueryService memberQueryService;

    @Mock
    private TokenVersionService tokenVersionService;

    private MemberSession memberSession;

    @BeforeEach
    void setUp() {
        // P3-2：网关快路径用"未配置网关凭据"的校验器 → isTrusted() 恒 false，
        // 本套件因此测的仍是**自行验签回退路径**（改造前的老逻辑）。
        // 快路径的行为由 GatewayIdentityMySqlTest 覆盖（那边需要真库/真 Redis）。
        // 参数顺序与 MemberSession 的字段顺序一致：jwtUtil, memberQueryService,
        // tokenVersionService, gatewayAuthVerifier, memberStatusCache
        memberSession = new MemberSession(jwtUtil, memberQueryService,
                tokenVersionService, new com.mall.trade.common.GatewayAuthVerifier(""),
                org.mockito.Mockito.mock(MemberStatusCache.class));
        // 模拟一次 HTTP 请求上下文(请求级缓存的载体)
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("同一请求内多次解析：只验签/取档案/读令牌版本号各一次(消除限流切面与控制器的重复解析)")
    void requireMember_cachedWithinSameRequest() {
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 3L));
        when(memberQueryService.snapshot(1L)).thenReturn(enabledMember());
        when(tokenVersionService.matches(TokenVersionService.TYPE_USER, 1L, 3L)).thenReturn(true);

        assertThat(memberSession.requireUserId(HEADER)).isEqualTo(1L);   // 限流切面调用
        assertThat(memberSession.requireUserId(HEADER)).isEqualTo(1L);   // 控制器调用
        assertThat(memberSession.requireMember(HEADER).getUsername()).isEqualTo("tom");
        assertThat(memberSession.requireToken(TOKEN).getId()).isEqualTo(1L);

        verify(jwtUtil, times(1)).parse(TOKEN);
        verify(memberQueryService, times(1)).snapshot(1L);
        verify(tokenVersionService, times(1)).matches(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("不同请求各自解析：换一个请求上下文后重新取档案(不会跨请求串号)")
    void requireMember_reResolvesForNewRequest() {
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 0L));
        when(memberQueryService.snapshot(1L)).thenReturn(enabledMember());
        when(tokenVersionService.matches(TokenVersionService.TYPE_USER, 1L, 0L)).thenReturn(true);

        memberSession.requireUserId(HEADER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        memberSession.requireUserId(HEADER);

        verify(memberQueryService, times(2)).snapshot(1L);
    }

    @Test
    @DisplayName("非请求上下文(如定时任务/异步线程)调用：不缓存也能正常解析，不抛 NPE")
    void requireMember_worksWithoutRequestContext() {
        RequestContextHolder.resetRequestAttributes();
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 0L));
        when(memberQueryService.snapshot(1L)).thenReturn(enabledMember());
        when(tokenVersionService.matches(TokenVersionService.TYPE_USER, 1L, 0L)).thenReturn(true);

        assertThat(memberSession.requireUserId(HEADER)).isEqualTo(1L);
        assertThat(memberSession.requireUserId(HEADER)).isEqualTo(1L);
        verify(memberQueryService, times(2)).snapshot(1L);   // 无请求上下文 → 每次都解析
    }

    @Test
    @DisplayName("双体系隔离：管理端 token 不能用于会员接口 → 401")
    void requireMember_rejectsAdminToken() {
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "admin", JwtUtil.TYPE_ADMIN, 0L));

        assertThatThrownBy(() -> memberSession.requireUserId(HEADER))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
        verify(memberQueryService, times(0)).snapshot(anyLong());
    }

    @Test
    @DisplayName("账号被禁用 → 401，且提示'账号已被禁用'")
    void requireMember_rejectsDisabledMember() {
        MemberSnapshotVO disabled = enabledMember();
        disabled.setStatus(0);
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 0L));
        when(memberQueryService.snapshot(1L)).thenReturn(disabled);

        assertThatThrownBy(() -> memberSession.requireUserId(HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号已被禁用");
    }

    @Test
    @DisplayName("会员已不存在(如已注销) → 401「登录已失效，请重新登录」")
    void requireMember_rejectsMissingMember() {
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 0L));
        when(memberQueryService.snapshot(1L)).thenReturn(null);

        assertThatThrownBy(() -> memberSession.requireUserId(HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录已失效，请重新登录");
    }

    @Test
    @DisplayName("令牌版本号不一致(登出/改密/被踢) → 401")
    void requireMember_rejectsStaleTokenVersion() {
        when(jwtUtil.parse(TOKEN)).thenReturn(new JwtUtil.Claims(1L, "tom", JwtUtil.TYPE_USER, 0L));
        when(memberQueryService.snapshot(1L)).thenReturn(enabledMember());
        when(tokenVersionService.matches(TokenVersionService.TYPE_USER, 1L, 0L)).thenReturn(false);

        assertThatThrownBy(() -> memberSession.requireUserId(HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录已失效，请重新登录");
    }

    @Test
    @DisplayName("缺少/格式错误的 Authorization 头 → 401 未登录")
    void requireMember_rejectsMissingHeader() {
        assertThatThrownBy(() -> memberSession.requireUserId(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
        assertThatThrownBy(() -> memberSession.requireUserId(TOKEN))   // 缺少 Bearer 前缀
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录");
    }

    /** 会员档案快照（构造器：id, username, nickname, phone, avatar, status, createTime） */
    private MemberSnapshotVO enabledMember() {
        return new MemberSnapshotVO(1L, "tom", "tom", null, null, 1, null);
    }
}
