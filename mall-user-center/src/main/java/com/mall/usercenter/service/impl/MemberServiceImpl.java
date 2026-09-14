package com.mall.usercenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.usercenter.domain.Member;
import com.mall.usercenter.dto.AuthResponse;
import com.mall.usercenter.dto.LoginRequest;
import com.mall.usercenter.dto.PasswordRequest;
import com.mall.usercenter.dto.RegisterRequest;
import com.mall.usercenter.dto.UserInfo;
import com.mall.usercenter.mapper.MemberMapper;
import com.mall.usercenter.service.MemberService;
import com.mall.usercenter.support.BusinessException;
import com.mall.usercenter.support.JwtUtil;
import com.mall.usercenter.support.RequestValidator;
import com.mall.usercenter.support.TokenVersionService;
import com.mall.usercenter.support.constant.EnableStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * 会员注册/登录实现。校验规则与《接口文档.md》2.1 一致。
 *
 * <p><b>登录态口径（P3）</b>：本类只负责"签发令牌"与"按 id 取会员"，**不校验令牌**——
 * 令牌的签名与版本号由网关校验（§4.4 ①）。{@code memberId} 是已经确定的身份
 * （来自 {@code @MemberId} 参数解析器读到的网关注入头）。
 */
@Service
@RequiredArgsConstructor
public class MemberServiceImpl implements MemberService {

    /** 用户名：4~20 位字母/数字/下划线 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{4,20}$");
    /** 手机号(选填时校验) */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    /** 与改造前逐字一致的文案（C1：对外错误表现不变） */
    private static final String MSG_RELOGIN = "登录已失效，请重新登录";
    private static final String MSG_DISABLED = "账号已被禁用";

    private final MemberMapper memberMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final TokenVersionService tokenVersionService;
    /** 请求参数校验(约束注解写在 DTO 字段上，此处统一触发) */
    private final RequestValidator requestValidator;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = trimToNull(request.getUsername());
        String password = request.getPassword();
        String phone = trimToNull(request.getPhone());

        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BusinessException(400, "用户名须为 4~20 位字母、数字或下划线");
        }
        requestValidator.check(request);
        if (phone != null && !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BusinessException(400, "手机号格式不正确");
        }
        if (findByUsername(username) != null) {
            throw new BusinessException(409, "用户名已存在");
        }
        if (phone != null && findByPhone(phone) != null) {
            throw new BusinessException(409, "手机号已被使用");
        }

        Member member = new Member();
        member.setUsername(username);
        member.setPassword(passwordEncoder.encode(password));
        member.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname().trim() : username);
        member.setPhone(phone);
        member.setStatus(EnableStatus.ENABLED);
        member.setGender(0);
        memberMapper.insert(member);
        return toAuthResponse(member);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String account = trimToNull(request.getAccount());
        if (account == null || request.getPassword() == null) {
            throw new BusinessException(400, "请输入账号和密码");
        }
        Member member = findByUsername(account);
        if (member == null) {
            member = findByPhone(account);
        }
        if (member == null || !passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (member.getStatus() == null || member.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(401, MSG_DISABLED);
        }
        member.setLastLoginTime(LocalDateTime.now());
        memberMapper.updateById(member);
        return toAuthResponse(member);
    }

    @Override
    public void logout(long memberId) {
        // 主动失效：令牌版本号 +1，该会员所有旧 token 立即不可用(Redis 不可用时自动降级为不失效)。
        // 网关下一次比对 mall:token:ver:user:{id} 时会拒绝旧 token —— 这里不需要、也拿不到 token 本身。
        tokenVersionService.bump(TokenVersionService.TYPE_USER, memberId);
    }

    @Override
    @Transactional(readOnly = true)
    public UserInfo getUserInfo(long memberId) {
        return toUserInfo(requireMember(memberId));
    }

    @Override
    @Transactional
    public void changePassword(long memberId, PasswordRequest request) {
        Member member = requireMember(memberId);
        if (!StringUtils.hasText(request.getOldPassword()) || !StringUtils.hasText(request.getNewPassword())) {
            throw new BusinessException(400, "请输入原密码和新密码");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), member.getPassword())) {
            throw new BusinessException(400, "原密码错误");
        }
        requestValidator.check(request);
        if (passwordEncoder.matches(request.getNewPassword(), member.getPassword())) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }
        member.setPassword(passwordEncoder.encode(request.getNewPassword()));
        memberMapper.updateById(member);
        // 改密后旧 token 立即失效（与登出同一条机制）
        tokenVersionService.bump(TokenVersionService.TYPE_USER, member.getId());
    }

    // ---------- private ----------

    private AuthResponse toAuthResponse(Member member) {
        return new AuthResponse(jwtUtil.createToken(member.getId(), member.getUsername(), JwtUtil.TYPE_USER,
                tokenVersionService.current(TokenVersionService.TYPE_USER, member.getId())),
                toUserInfo(member));
    }

    private UserInfo toUserInfo(Member m) {
        return UserInfo.builder()
                .id(m.getId())
                .username(m.getUsername())
                .nickname(m.getNickname())
                .avatar(m.getAvatar())
                .phone(m.getPhone())
                .status(m.getStatus())
                .createTime(m.getCreateTime())
                .build();
    }

    /**
     * 按 id 取会员：**身份已由网关注入并校验过**（见 {@code GatewayIdentityResolver}），
     * 这里只负责取数据 + 判定会员域自己的业务前提（存在、未被禁用）。
     *
     * <p>改造前这两件事在 {@code MemberSession} 里（它顺带验签、查库、读令牌版本）；
     * 现在验签与令牌版本属于网关，剩下两条留在属主这里，对外文案逐字不变：
     * 查不到 → 401「登录已失效，请重新登录」（解析与取数之间被删除/逻辑删除的竞态也走这条），
     * 被禁用 → 401「账号已被禁用」。
     */
    private Member requireMember(long memberId) {
        Member member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        if (member.getStatus() == null || member.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(401, MSG_DISABLED);
        }
        return member;
    }

    private Member findByUsername(String username) {
        return memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getUsername, username));
    }

    private Member findByPhone(String phone) {
        return memberMapper.selectOne(new LambdaQueryWrapper<Member>()
                .eq(Member::getPhone, phone));
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
