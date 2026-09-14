package com.mall.demo.auth.support;

import com.mall.demo.common.AuthHeader;
import com.mall.demo.common.BusinessException;
import com.mall.demo.common.GatewayAuthHeaders;
import com.mall.demo.common.GatewayAuthVerifier;
import com.mall.demo.common.MemberStatusCache;
import com.mall.demo.common.constant.EnableStatus;
import com.mall.demo.common.contract.MemberQueryService;
import com.mall.demo.common.dto.MemberSnapshotVO;
import com.mall.demo.common.dto.MemberStatusVO;
import com.mall.demo.common.JwtUtil;
import com.mall.demo.common.TokenVersionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从请求头解析当前登录会员。供本进程里所有需要登录态的接口（{@code @MemberId} / {@code @AuthToken}）使用。
 *
 * <h2>P3 起的两条路径（这是登录态改造的核心）</h2>
 * <ol>
 *   <li><b>网关快路径</b>：请求带 {@code X-Gateway-Auth}（共享密钥，校验见
 *       {@link GatewayAuthVerifier}）时，说明**网关已经验签并比对过令牌版本**，
 *       身份以 {@code X-Member-Id} 为准。本进程不再验签、不再读令牌版本号；
 *       会员"还在不在/禁没禁用"先问 {@link MemberStatusCache}（热路径完全不碰库）。</li>
 *   <li><b>自行验签回退</b>：没有可信网关身份时（直连服务端口、排障、网关关闭鉴权），
 *       走改造前的老逻辑：验签 → {@code typ=user} → 取会员档案 → 状态 → 令牌版本。</li>
 * </ol>
 *
 * <p><b>为什么保留回退而不是直接删掉</b>：① 直连 8080 仍然是合法的运维/排障路径，
 * 删掉等于把它变成匿名；② 网关的 {@code enabled=false} 一键回退需要它；③ 真库测试直接打 MockMvc
 * （不经过网关）。
 *
 * <p><b>P3-4 的关键改动：回退路径不再直连本地表。</b>
 * 会员数据已经归 {@code mall-user-center}（库 {@code mall_user}），单体里没有
 * {@code ums_member}、也没有 {@code Member} 实体与 {@code MemberMapper} 了——
 * 按 id 取会员改为走域契约 {@link MemberQueryService#snapshot(Long)}（远程模式下即
 * {@code /internal/v1/user/member/{id}/snapshot}）。
 * 这正是 P0 坚持"跨域只走域服务接口"的回报：**接口方法签名一个字没改，只换了实现**。
 * （曾经直连本地表的版本在切换当天报过真实故障：新注册会员只写 {@code mall_user}，
 * 单体查本地表查不到 → 下单 401。）
 *
 * <p>两条路径**产出完全相同的对外表现**（同样的 401 文案、同样的"禁用→401"），
 * 所以调用方（{@code @MemberId} 参数解析器、限流切面、各业务服务）完全不必知道自己走的是哪条。
 *
 * <p>⚠️ 快路径下拿到的 {@link MemberSnapshotVO} 可能来自状态缓存旁边的"只有 id/nickname/status"
 * 的场景；需要完整字段的调用方请用 {@link #requireMember(String)}——它按 id 回源取档案；
 * 只有 {@code @MemberId} 这类"只要 id"的场景才走纯缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberSession {

    /** 请求级属性名：缓存本次请求已解析的会员 */
    private static final String ATTR_MEMBER = MemberSession.class.getName() + ".resolved";

    /** 请求级属性名：网关快路径解析出的会员 id（同一请求内可能被限流切面与参数解析器各问一次） */
    private static final String ATTR_GATEWAY_ID = MemberSession.class.getName() + ".gatewayId";

    /** 请求级属性名：网关快路径下按 id 回源取到的完整档案（供 {@link #requireMember} 复用） */
    private static final String ATTR_GATEWAY_MEMBER = MemberSession.class.getName() + ".gatewayMember";

    /** 与改造前逐字一致的文案（C1：对外错误表现不变） */
    private static final String MSG_RELOGIN = "登录已失效，请重新登录";
    private static final String MSG_DISABLED = "账号已被禁用";
    private static final String MSG_WRONG_TYPE = "登录已失效，请使用会员账号操作";

    private final JwtUtil jwtUtil;
    /** 会员档案的域契约：P3-4 起只有"调 user-center"一种实现（见类注释） */
    private final MemberQueryService memberQueryService;
    private final TokenVersionService tokenVersionService;
    private final GatewayAuthVerifier gatewayAuthVerifier;
    private final MemberStatusCache memberStatusCache;

    /** 解析 Authorization: Bearer xxx，返回当前会员 id(未登录/失效/禁用 → 401)；参数为 HTTP 头原样 */
    public Long requireUserId(String authorization) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            // 热路径：网关身份 + 成员状态缓存，通常一次库都不查
            Long id = resolveIdFromGateway(attrs.getRequest());
            if (id != null) {
                return id;
            }
        }
        return requireMember(authorization).getId();
    }

    /** 解析 Authorization: Bearer xxx，返回当前会员档案；参数为 **HTTP 头原样**(含 "Bearer ") */
    public MemberSnapshotVO requireMember(String authorization) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            Long id = resolveIdFromGateway(attrs.getRequest());
            if (id != null) {
                // 完整档案的调用方需要 username/phone/avatar 等字段，这些不在状态缓存里
                // → 按 id 回源取一次档案（与改造前的开销相同，但省掉了验签与令牌版本读取）
                return requireFullMember(id);
            }
        }
        return requireToken(AuthHeader.bearer(authorization));
    }

    /**
     * 解析**已剥离 "Bearer " 的裸 token**，返回当前会员档案。
     * 供 Service 层使用(Controller 传入 Service 的已经是裸 token，不能再走 {@link #requireMember})。
     *
     * <p>本方法**始终做完整校验**（不走近网关快路径）：它接收的是一个显式传入的 token，
     * 语义就是"验证这个 token"。HTTP 入口请用 {@link #requireUserId}/{@link #requireMember}。
     */
    public MemberSnapshotVO requireToken(String token) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            Object cached = attrs.getAttribute(ATTR_MEMBER, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Resolved resolved && resolved.token().equals(token)) {
                return resolved.member();   // 同一请求内复用，省去重复的会员查询与令牌版本读取
            }
        }
        MemberSnapshotVO member = resolve(token);
        if (attrs != null) {
            attrs.setAttribute(ATTR_MEMBER, new Resolved(token, member), RequestAttributes.SCOPE_REQUEST);
        }
        return member;
    }

    // ==================== 网关快路径 ====================

    /**
     * 从网关注入的身份头解析会员 id；不可信 / 不适用时返回 {@code null}（调用方回退到自行验签）。
     *
     * <p>校验顺序：网关凭据 → 会员 id 可解析 → 令牌版本复核 → 会员存在性/状态。
     * 前两步"不可信"返回 null（回退），后两步是**已经确定身份之后的业务判定**，
     * 失败必须抛 401（与改造前一样，不能悄悄退回匿名）。
     */
    private Long resolveIdFromGateway(HttpServletRequest request) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            Object cached = attrs.getAttribute(ATTR_GATEWAY_ID, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof Long id) {
                return id;   // 同一请求内复用（限流切面 + @MemberId 解析器都会问一次）
            }
        }
        if (!gatewayAuthVerifier.isTrusted(request)) {
            return null;
        }
        // 快路径的"证据日志"：排障时能一眼看出"这次请求是网关给的身份证，还是我自己验的签"
        log.debug("网关身份快路径: memberIdHeader={}", request.getHeader(GatewayAuthHeaders.MEMBER_ID));
        Long memberId = parseLong(request.getHeader(GatewayAuthHeaders.MEMBER_ID));
        if (memberId == null) {
            return null;
        }
        // 纵深防御：网关已经比对过令牌版本，这里用同一个 Redis key 再核一次（几乎零成本）。
        // TokenVersionService.matches 自身在 Redis 不可用时 fail-open，与改造前口径一致。
        long ver = parseLong(request.getHeader(GatewayAuthHeaders.MEMBER_VER), 0L);
        if (!tokenVersionService.matches(TokenVersionService.TYPE_USER, memberId, ver)) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        MemberStatusVO cached = memberStatusCache.get(memberId);
        if (cached != null) {
            checkEnabled(cached.status());
            if (attrs != null) {
                attrs.setAttribute(ATTR_GATEWAY_ID, memberId, RequestAttributes.SCOPE_REQUEST);
            }
            return memberId;
        }
        // 缓存未命中：走**域契约**取会员快照
        // （实现=调 user-center 内部接口；本进程已无 ums_member，也不允许直连）。
        MemberSnapshotVO snapshot = memberQueryService.snapshot(memberId);
        if (snapshot == null) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        checkEnabled(snapshot.getStatus());
        memberStatusCache.put(memberId, snapshot.getNickname(), snapshot.getStatus());
        if (attrs != null) {
            attrs.setAttribute(ATTR_GATEWAY_ID, memberId, RequestAttributes.SCOPE_REQUEST);
        }
        return memberId;
    }

    /** 快路径下需要完整档案时：按 id 回源，并顺带回填状态缓存（同一请求内复用结果） */
    private MemberSnapshotVO requireFullMember(Long memberId) {
        ServletRequestAttributes attrs = currentRequest();
        if (attrs != null) {
            Object cached = attrs.getAttribute(ATTR_GATEWAY_MEMBER, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof MemberSnapshotVO member) {
                return member;
            }
        }
        MemberSnapshotVO member = memberQueryService.snapshot(memberId);
        if (member == null) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        checkEnabled(member.getStatus());
        memberStatusCache.put(member.getId(), member.getNickname(), member.getStatus());
        if (attrs != null) {
            attrs.setAttribute(ATTR_GATEWAY_MEMBER, member, RequestAttributes.SCOPE_REQUEST);
        }
        return member;
    }

    // ==================== 自行验签（回退路径，改造前的老逻辑） ====================

    /** 真正的校验逻辑(不对外暴露，避免绕过请求级缓存) */
    private MemberSnapshotVO resolve(String token) {
        JwtUtil.Claims claims = jwtUtil.parse(token);
        if (!JwtUtil.TYPE_USER.equals(claims.type())) {
            throw new BusinessException(401, MSG_WRONG_TYPE);
        }
        MemberSnapshotVO member = memberQueryService.snapshot(claims.userId());
        if (member == null) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        if (member.getStatus() == null || member.getStatus() != EnableStatus.ENABLED) {
            throw new BusinessException(401, MSG_DISABLED);
        }
        if (!tokenVersionService.matches(TokenVersionService.TYPE_USER, member.getId(), claims.ver())) {
            throw new BusinessException(401, MSG_RELOGIN);
        }
        // 顺便回填状态缓存：下一次请求（即使仍走回退路径）也能命中，逐步减少查库
        memberStatusCache.put(member.getId(), member.getNickname(), member.getStatus());
        return member;
    }

    private static void checkEnabled(Integer status) {
        if (status == null || status != EnableStatus.ENABLED) {
            throw new BusinessException(401, MSG_DISABLED);
        }
    }

    private static Long parseLong(String raw) {
        return parseLong(raw, null);
    }

    private static Long parseLong(String raw, Long defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private ServletRequestAttributes currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs ? attrs : null;
    }

    /** 单次请求内已解析的会员；连同 token 一起存，避免同一请求里出现不同 token 时被误复用 */
    private record Resolved(String token, MemberSnapshotVO member) {
    }
}
