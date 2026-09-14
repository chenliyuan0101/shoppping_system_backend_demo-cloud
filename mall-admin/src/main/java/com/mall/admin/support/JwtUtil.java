package com.mall.admin.support;

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
 * 极简 HS256 JWT 实现(纯 JDK，无第三方依赖)。
 * 声明：sub=userId, name=username, typ=user|admin(区分两套体系), iat, exp, ver。
 *
 * <h2>⚠️ 这是**签发方**（P7 §3 的硬要求：令牌必须与单体逐字一致）</h2>
 * 逐字照抄单体 {@code com.mall.demo.common.JwtUtil}（本服务自持副本），包括：
 * <ul>
 *   <li>header 恒为 {@code {"alg":"HS256","typ":"JWT"}}、base64url **无填充**；</li>
 *   <li>payload 字段顺序 {@code sub,name,typ,ver,iat,exp}（网关/单体用正则取 claims，顺序不影响判定，
 *       但保持逐字可让"同一秒、同一版本、同一管理员"签出的令牌**字节相同**，便于对撞验证）；</li>
 *   <li>{@code exp = iat + expireHours*3600}（**没有**时钟宽容窗口）、{@code exp < now} 即失效；</li>
 *   <li>验签用 {@link MessageDigest#isEqual} 恒定时比较；</li>
 *   <li>失败一律 {@code 401 登录已失效，请重新登录}（与单体/网关逐字一致）。</li>
 * </ul>
 * 网关 {@code AdminIdentityFilter} 与本服务共用同一把密钥（{@code mall.jwt.secret}）与同一套 claims 口径，
 * 因此本服务登录签发的令牌**不需要改网关一行代码**就能被验签（P7-gateway-admin-auth-report §2）。
 * ⚠️ 密钥配置不当（缺失/短于 32 位）**直接拒绝启动**——与单体同一条安全加固。
 */
@Component
public class JwtUtil {

    public static final String TYPE_USER = "user";
    public static final String TYPE_ADMIN = "admin";

    private static final Pattern SUB_PATTERN = Pattern.compile("\"sub\":\\s*(\\d+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TYP_PATTERN = Pattern.compile("\"typ\":\\s*\"([^\"]+)\"");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\":\\s*(\\d+)");
    /** 令牌版本号(登出/禁用后 +1，使旧 token 立即失效) */
    private static final Pattern VER_PATTERN = Pattern.compile("\"ver\":\\s*(\\d+)");

    private final String secret;
    private final long expireHours;

    public JwtUtil(@Value("${mall.jwt.secret:}") String secret,
                   @Value("${mall.jwt.expire-hours:24}") long expireHours) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "mall.jwt.secret 未配置：请在 application-dev.yaml 或环境变量 MALL_JWT_SECRET 中提供至少 32 位随机串");
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException("mall.jwt.secret 过短(至少 32 位)，请换成随机长串");
        }
        this.secret = secret.trim();
        this.expireHours = expireHours;
    }

    /**
     * 生成 Token(唯一入口，必须带上当前令牌版本号)。
     * 版本号由 {@link TokenVersionService#current(String, long)} 提供——不要写死 0：
     * 若当前版本号已 >0(该账号登出/被禁用过)，写死 0 的 token 会被自己立刻判为失效。
     */
    public String createToken(Long userId, String username, String type, long ver) {
        long now = Instant.now().getEpochSecond();
        String payloadJson = "{\"sub\":" + userId
                + ",\"name\":\"" + escape(username) + "\""
                + ",\"typ\":\"" + escape(type) + "\""
                + ",\"ver\":" + ver
                + ",\"iat\":" + now
                + ",\"exp\":" + (now + expireHours * 3600) + "}";
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(payloadJson);
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    /** 解析并校验 Token，非法/过期抛 401「登录已失效，请重新登录」 */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized();
        }
        String signingInput = parts[0] + "." + parts[1];
        if (!safeEquals(sign(signingInput), parts[2])) {
            throw unauthorized();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw unauthorized();
        }
        Matcher subMatcher = SUB_PATTERN.matcher(payload);
        Matcher expMatcher = EXP_PATTERN.matcher(payload);
        if (!subMatcher.find() || !expMatcher.find()) {
            throw unauthorized();
        }
        long exp = Long.parseLong(expMatcher.group(1));
        if (exp < Instant.now().getEpochSecond()) {
            throw unauthorized();
        }
        long userId = Long.parseLong(subMatcher.group(1));
        String username = extractName(payload);
        String type = extractType(payload);
        long ver = extractVer(payload);
        return new Claims(userId, username, type, ver);
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
        String raw = m.group(1);
        return raw.replace("\\\"", "\"").replace("\\\\", "\\");
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

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException unauthorized() {
        return new BusinessException(401, "登录已失效，请重新登录");
    }

    /** 解析结果 */
    public record Claims(Long userId, String username, String type, long ver) {
    }
}
