package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.mall.usercenter.domain.Member;
import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.LoginRequest;
import com.mall.usercenter.dto.PasswordRequest;
import com.mall.usercenter.dto.RegisterRequest;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.service.MemberService;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.JwtUtil;
import com.mall.usercenter.support.RequestValidator;
import com.mall.usercenter.support.TokenVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 注册/登录/改密 Service 单测(Mockito，无需数据库/Redis)。
 *
 * <p>从单体平移：构造参数里**不再有 {@code MemberSession}**——本服务收的是已确定的 memberId
 * （网关注入身份），校验只剩"会员存在 + 未被禁用"。
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberMapper memberMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TokenVersionService tokenVersionService;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 参数校验走真实实现(约束在 DTO 上)，不用 mock，避免把校验绕过 */
    private final RequestValidator requestValidator =
            new RequestValidator(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());

    private MemberService service;

    private static final String RAW_PASSWORD = "Abc123456";

    @BeforeEach
    void setUp() {
        service = new MemberServiceImpl(memberMapper, encoder, jwtUtil, tokenVersionService, requestValidator);
    }

    @Test
    @DisplayName("注册成功：返回 token 与用户信息，密码以 BCrypt 密文入库")
    void register_ok() {
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        // 真库下 insert 会回填自增 id；这里同样回填，token 签发需要用到 id
        when(memberMapper.insert(any(Member.class))).thenAnswer(inv -> {
            ((Member) inv.getArgument(0)).setId(1001L);
            return 1;
        });
        when(jwtUtil.createToken(any(), any(), any(), anyLong())).thenReturn("token-register");

        RegisterRequest request = new RegisterRequest();
        request.setUsername("tom2026");
        request.setPassword(RAW_PASSWORD);
        request.setNickname("汤姆");

        AuthResponse response = service.register(request);

        assertThat(response.getToken()).isEqualTo("token-register");
        assertThat(response.getUser().getUsername()).isEqualTo("tom2026");
        assertThat(response.getUser().getNickname()).isEqualTo("汤姆");

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).insert(captor.capture());
        Member saved = captor.getValue();
        assertThat(saved.getPassword()).isNotEqualTo(RAW_PASSWORD);
        assertThat(encoder.matches(RAW_PASSWORD, saved.getPassword())).isTrue();
        assertThat(saved.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("注册：用户名不合法 → 400")
    void register_invalidUsername() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("a!");   // 过短且含非法字符
        request.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("注册：密码强度不足 → 400")
    void register_weakPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("tom2026");
        request.setPassword("123456");   // 无字母

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("注册：用户名已存在 → 409")
    void register_duplicateUsername() {
        Member existing = new Member();
        existing.setId(1L);
        existing.setUsername("tom2026");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        RegisterRequest request = new RegisterRequest();
        request.setUsername("tom2026");
        request.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("登录成功(用户名)：校验通过并返回 token")
    void login_ok() {
        Member member = memberWithHash(RAW_PASSWORD);
        member.setUsername("tom2026");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        when(jwtUtil.createToken(any(), any(), any(), anyLong())).thenReturn("token-login");

        LoginRequest request = new LoginRequest();
        request.setAccount("tom2026");
        request.setPassword(RAW_PASSWORD);

        AuthResponse response = service.login(request);

        assertThat(response.getToken()).isEqualTo("token-login");
        assertThat(response.getUser().getId()).isEqualTo(1L);
        verify(memberMapper).updateById(any(Member.class));
    }

    @Test
    @DisplayName("登录成功(手机号)：用户名查不到时按手机号查")
    void login_byPhone() {
        Member member = memberWithHash(RAW_PASSWORD);
        member.setPhone("13800138000");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null, member);
        when(jwtUtil.createToken(any(), any(), any(), anyLong())).thenReturn("token-phone");

        LoginRequest request = new LoginRequest();
        request.setAccount("13800138000");
        request.setPassword(RAW_PASSWORD);

        AuthResponse response = service.login(request);

        assertThat(response.getToken()).isEqualTo("token-phone");
        assertThat(response.getUser().getPhone()).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("登录：密码错误 → 401")
    void login_wrongPassword() {
        Member member = memberWithHash("Other9999");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        LoginRequest request = new LoginRequest();
        request.setAccount("tom2026");
        request.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("登录：账号不存在 → 401")
    void login_accountNotFound() {
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        LoginRequest request = new LoginRequest();
        request.setAccount("nobody");
        request.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("登录：账号被禁用 → 401「账号已被禁用」")
    void login_disabledAccount() {
        Member member = memberWithHash(RAW_PASSWORD);
        member.setStatus(0);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        LoginRequest request = new LoginRequest();
        request.setAccount("tom2026");
        request.setPassword(RAW_PASSWORD);

        assertThatThrownBy(() -> service.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号已被禁用")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("当前用户信息：身份有效（网关注入）→ 按 id 查库返回用户信息")
    void getUserInfo_ok() {
        Member member = memberWithHash(RAW_PASSWORD);
        when(memberMapper.selectById(1L)).thenReturn(member);

        assertThat(service.getUserInfo(1L).getUsername()).isEqualTo("tom2026");
    }

    @Test
    @DisplayName("当前用户信息：会员不存在（被删除/逻辑删除）→ 401「登录已失效，请重新登录」")
    void getUserInfo_memberMissing() {
        when(memberMapper.selectById(4242L)).thenReturn(null);

        assertThatThrownBy(() -> service.getUserInfo(4242L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("登录已失效，请重新登录")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("当前用户信息：会员被禁用 → 401「账号已被禁用」（令牌是否有效由网关判，这里判会员域状态）")
    void getUserInfo_disabledMember() {
        Member disabled = memberWithHash(RAW_PASSWORD);
        disabled.setStatus(0);
        when(memberMapper.selectById(1L)).thenReturn(disabled);

        assertThatThrownBy(() -> service.getUserInfo(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号已被禁用")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("改密成功：落库新密文并 bump 令牌版本号（旧 token 立即失效）")
    void changePassword_ok() {
        Member member = memberWithHash(RAW_PASSWORD);
        when(memberMapper.selectById(1L)).thenReturn(member);

        PasswordRequest request = new PasswordRequest();
        request.setOldPassword(RAW_PASSWORD);
        request.setNewPassword("New123456");

        service.changePassword(1L, request);

        ArgumentCaptor<Member> captor = ArgumentCaptor.forClass(Member.class);
        verify(memberMapper).updateById(captor.capture());
        assertThat(encoder.matches("New123456", captor.getValue().getPassword())).isTrue();
        verify(tokenVersionService).bump(TokenVersionService.TYPE_USER, 1L);
    }

    @Test
    @DisplayName("改密：原密码错误 → 400「原密码错误」，且不落库、不 bump 版本号")
    void changePassword_wrongOldPassword() {
        Member member = memberWithHash(RAW_PASSWORD);
        when(memberMapper.selectById(1L)).thenReturn(member);

        PasswordRequest request = new PasswordRequest();
        request.setOldPassword("Wrong9999");
        request.setNewPassword("New123456");

        assertThatThrownBy(() -> service.changePassword(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("原密码错误")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(400));
    }

    @Test
    @DisplayName("登出：只 bump 令牌版本号（不需要 token，也不需要查会员）")
    void logout_bumpsTokenVersion() {
        service.logout(5L);

        verify(tokenVersionService).bump(TokenVersionService.TYPE_USER, 5L);
    }

    private Member memberWithHash(String raw) {
        Member member = new Member();
        member.setId(1L);
        member.setUsername("tom2026");
        member.setPassword(encoder.encode(raw));
        member.setNickname("汤姆");
        member.setStatus(1);
        return member;
    }
}
