package com.mall.gateway.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 HS256 JWT 的**网关侧副本**（与单体 {@code com.mall.demo.common.JwtUtil} 同算法、同 claims）。
 *
 * <p>为什么复制而不是抽公共 jar：网关是 WebFlux 独立进程，且这是"验签"这种**必须与签发方逐字节一致**
 * 的东西——抽成共享库看似省事，实际会把"网关能不能升级"和"用户中心能不能升级"重新绑死
 * （§4.10 的取舍：契约靠逐字相同 + 契约测试守，而不是靠同一个 class 文件）。
 *
 * <p>与单体那版的差别只有一处：失败不再抛 {@code BusinessException}（网关不依赖单体的异常体系），
 * 而是抛 {@link InvalidTokenException}，由过滤器转成统一的 401 响应体。
 *
 * <p>claims（顺序与单体一致）：{@code sub}=用户 id、{@code name}=用户名、{@code typ}=user|admin、
 * {@code ver}=令牌版本号、{@code iat}、{@code exp}。**解析不校验 typ/ver**——那是调用方的事
 * （单体里由 {@code MemberSession} 做，网关里由过滤器做）。
 */
@Component
public class GatewayJwtUtil {

    public static final String TYPE_USER = "user";
    public static final String TYPE_ADMIN = "admin";

    private static final Pattern SUB_PATTERN = Pattern.compile("\"sub\":\\s*(\\d+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TYP_PATTERN = Pattern.compile("\"typ\":\\s*\"([^\"]+)\"");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\":\\s*(\\d+)");
    private static final Pattern VER_PATTERN = Pattern.compile("\"ver\":\\s*(\\d+)");

    private final String secret;

    public GatewayJwtUtil(@Value("${mall.jwt.secret:}") String secret) {
        // 与单体同一套"拒绝静默兜底"的策略：密钥缺失/过短直接启动失败。
        // 否则最容易发生的事故是"网关用空密钥验签，把所有请求判成未登录"，或者反过来漏验。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "mall.jwt.secret 未配置：网关必须与签发方（mall-user-center）使用同一把密钥");
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException("mall.jwt.secret 过短(至少 32 位)，请与用户中心保持一致");
        }
        this.secret = secret.trim();
    }

    /** 解析并校验签名与有效期；任何非法/过期都抛 {@link InvalidTokenException} */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new InvalidTokenException();
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!safeEquals(sign(signingInput), parts[2])) {
            throw new InvalidTokenException();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
        Matcher subMatcher = SUB_PATTERN.matcher(payload);
        Matcher expMatcher = EXP_PATTERN.matcher(payload);
        if (!subMatcher.find() || !expMatcher.find()) {
            throw new InvalidTokenException();
        }
        if (Long.parseLong(expMatcher.group(1)) < Instant.now().getEpochSecond()) {
            throw new InvalidTokenException();
        }
        return new Claims(Long.parseLong(subMatcher.group(1)), extractName(payload),
                extractType(payload), extractVer(payload));
    }

    private long extractVer(String payload) {
        Matcher m = VER_PATTERN.matcher(payload);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private String extractName(String payload) {
        Matcher m = NAME_PATTERN.matcher(payload);
        if (!m.find()) {
            return null;
        }
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String extractType(String payload) {
        Matcher m = TYP_PATTERN.matcher(payload);
        return m.find() ? m.group(1) : null;
    }

    private String sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sign token failed", e);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析结果 */
    public record Claims(Long userId, String username, String type, long ver) {
    }
}
