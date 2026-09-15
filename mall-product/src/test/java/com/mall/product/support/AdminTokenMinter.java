package com.mall.product.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import com.mall.common.support.MemberId;

/**
 * 测试用的**管理员 JWT 签发器**（P6-1b）。
 *
 * <h2>为什么要单独造一个，而不用被测的类</h2>
 * 被测的 {@code JwtVerifier} 只有解析、没有签发（本服务不签发令牌）。
 * 如果测试用它自己（或它的一部分）来造令牌，那就成了"用被测物验证被测物"——
 * 一个"验签算法写错、签发也按同样错法写"的实现会自证通过。
 * 所以这里**从零手写**一段 HS256 签发（纯 JDK、base64url 无填充、claims 字段名与单体一致），
 * 它只依赖"JWT 是什么"这个公开事实，不依赖本仓库的任何实现类 ⇒ 它是一把**独立的尺子**。
 *
 * <p>另一条纪律：密钥**不是**测试专用值 —— 由调用方传入**运行时配置里的那一把**
 * （{@code @Value("${mall.jwt.secret}")}），因此测试同时证明了"部署配置里的密钥是能验通的"。
 * 想验"别的密钥签的令牌必须被拒"，就显式传一个坏密钥进来（见 {@link #withSecret(String)}）。
 */
public final class AdminTokenMinter {

    private AdminTokenMinter() {
    }

    /** 用给定密钥签一个管理员令牌（typ=admin，有效期 1 小时） */
    public static String admin(String secret, long adminId) {
        return token(secret, adminId, "admin" + adminId, "admin", 0L, 3600L);
    }

    /** 用给定密钥签一个**会员**令牌（typ=user，用于验双体系隔离） */
    public static String member(String secret, long memberId) {
        return token(secret, memberId, "member" + memberId, "user", 0L, 3600L);
    }

    /** 已过期 60 秒的管理员令牌（用于验有效期检查） */
    public static String expiredAdmin(String secret, long adminId) {
        return token(secret, adminId, "admin" + adminId, "admin", 0L, -60L);
    }

    /** 用一个明显不对的密钥签管理员令牌（用于验签名校验真的在生效） */
    public static String withSecret(String otherSecret, long adminId) {
        return token(otherSecret, adminId, "admin" + adminId, "admin", 0L, 3600L);
    }

    /** 任意 claims 的签发入口（claims 名与单体 JwtUtil 逐字一致：sub/name/typ/ver/iat/exp） */
    public static String token(String secret, long subject, String name, String type, long ver, long ttlSeconds) {
        long now = Instant.now().getEpochSecond();
        String payload = "{\"sub\":" + subject
                + ",\"name\":\"" + name + "\""
                + ",\"typ\":\"" + type + "\""
                + ",\"ver\":" + ver
                + ",\"iat\":" + now
                + ",\"exp\":" + (now + ttlSeconds) + "}";
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String body = b64(payload);
        String signingInput = header + "." + body;
        return signingInput + "." + sign(secret, signingInput);
    }

    private static String sign(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
