package com.mall.usercenter.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.domain.Member;
import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.LoginRequest;
import com.mall.usercenter.dto.RegisterRequest;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.JwtUtil;
import com.mall.usercenter.support.UserCenterTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册/登录 <b>真实 MySQL 集成测试</b>（全量 Spring 上下文，连接 application-dev.yaml 的 {@code mall_user} 库）。
 * 每次运行真实插入并断言后物理删除测试用户(可重复执行)，用户名带唯一后缀避免与 seed 冲突。
 *
 * <p>从单体 {@code com.mall.demo.auth.MemberAuthMySqlTest} 平移过来：本套件直接调 {@code memberService}，
 * 不经过 HTTP，因此**与登录态改造（网关注入身份）无关**，只有包名与数据源（mall → mall_user）变了。
 */
class MemberAuthMySqlTest extends UserCenterTestBase {

    private static final String PASSWORD = "Db123456";

    @Autowired
    private MemberService memberService;

    @Autowired
    private MemberMapper memberMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    private final List<String> createdUsers = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String username : createdUsers) {
            jdbcTemplate.update("DELETE FROM ums_member WHERE username = ?", username);
        }
        createdUsers.clear();
    }

    @Test
    @DisplayName("[MySQL] 注册成功：数据真实落库且密码 BCrypt 加密，返回可用 token")
    void register_persistsToMySql() {
        String username = nextUsername();
        createdUsers.add(username);

        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        request.setNickname("集成测试用户");

        AuthResponse response = memberService.register(request);

        assertThat(response.getToken()).isNotBlank();
        assertThat(response.getUser().getId()).isNotNull();

        Member row = memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getUsername, username));
        assertThat(row).isNotNull();
        assertThat(row.getStatus()).isEqualTo(1);
        assertThat(passwordEncoder.matches(PASSWORD, row.getPassword())).isTrue();

        JwtUtil.Claims claims = jwtUtil.parse(response.getToken());
        assertThat(claims.userId()).isEqualTo(row.getId());
        assertThat(claims.username()).isEqualTo(username);
        // 新会员的令牌版本号从 0 开始：网关据此比对 mall:token:ver:user:{id}
        assertThat(claims.ver()).isZero();
    }

    @Test
    @DisplayName("[MySQL] 注册后可用用户名登录，并可用手机号登录")
    void register_thenLoginByUsernameAndPhone() {
        String username = nextUsername();
        String phone = "139" + String.format("%08d", (int) (System.nanoTime() % 100000000));
        createdUsers.add(username);

        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        request.setPhone(phone);

        AuthResponse registered = memberService.register(request);
        assertThat(registered.getToken()).isNotBlank();

        LoginRequest byName = new LoginRequest();
        byName.setAccount(username);
        byName.setPassword(PASSWORD);
        assertThat(memberService.login(byName).getUser().getUsername()).isEqualTo(username);

        LoginRequest byPhone = new LoginRequest();
        byPhone.setAccount(phone);
        byPhone.setPassword(PASSWORD);
        assertThat(memberService.login(byPhone).getUser().getPhone()).isEqualTo(phone);
    }

    @Test
    @DisplayName("[MySQL] 重复注册同名用户 → 409(与库中已存在用户比较)")
    void register_duplicateUsername_conflictWithDb() {
        // 与单体版本唯一的差别：先真实注册一个唯一用户名再重复注册，而不是依赖 seed 里的 "demo"
        // ——断言的是"唯一性确实由数据库里的已有行决定"，不依赖本机 seed 数据是否一致。
        String username = nextUsername();
        createdUsers.add(username);

        RegisterRequest first = new RegisterRequest();
        first.setUsername(username);
        first.setPassword(PASSWORD);
        memberService.register(first);

        RegisterRequest again = new RegisterRequest();
        again.setUsername(username);
        again.setPassword(PASSWORD);

        assertThatThrownBy(() -> memberService.register(again))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("[MySQL] 密码错误 → 401；账号不存在 → 401")
    void login_wrongPasswordOrNotExist() {
        String username = nextUsername();
        createdUsers.add(username);
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        memberService.register(request);

        LoginRequest wrong = new LoginRequest();
        wrong.setAccount(username);
        wrong.setPassword("Wrong9999");
        assertThatThrownBy(() -> memberService.login(wrong))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));

        LoginRequest missing = new LoginRequest();
        missing.setAccount("no_such_user_" + System.nanoTime() % 10000);
        missing.setPassword(PASSWORD);
        assertThatThrownBy(() -> memberService.login(missing))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    @Test
    @DisplayName("[MySQL] 被禁用的会员：登录 → 401「账号已被禁用」")
    void login_disabledMember() {
        String username = nextUsername();
        createdUsers.add(username);
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        AuthResponse registered = memberService.register(request);

        jdbcTemplate.update("UPDATE ums_member SET status = 0 WHERE id = ?", registered.getUser().getId());

        LoginRequest login = new LoginRequest();
        login.setAccount(username);
        login.setPassword(PASSWORD);
        assertThatThrownBy(() -> memberService.login(login))
                .isInstanceOf(BusinessException.class)
                .hasMessage("账号已被禁用")
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(401));
    }

    private static String nextUsername() {
        return "uctest_" + (System.nanoTime() % 100_000_000L);
    }
}
