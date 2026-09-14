package com.mall.product.config;

import com.mall.product.support.BusinessException;
import lombok.extern.slf4j.Slf4j;
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
 * 管理端 JWT 的 <b>验签器</b>（P6-1b）。
 *
 * <h2>来源与"逐字沿用"的范围</h2>
 * 代码照抄单体 {@code com.mall.demo.common.JwtUtil} 的**解析/校验**部分（纯 JDK HS256，无第三方库），
 * 包括：三段结构、{@code MessageDigest.isEqual} 恒定时比较、base64url **无填充**、
 * {@code exp < now} 即失效（**没有**时钟宽容窗口）、claims 正则提取（{@code sub/name/typ/ver/exp}）。
 * 失败一律 {@code BusinessException(401, "登录已失效，请重新登录")}（文案与单体逐字一致）。
 *
 * <h2>为什么这里**没有** {@code createToken}</h2>
 * 单体那个类是"签发 + 校验"一把梭（它自己发令牌）。本服务**只验签、绝不签发**：
 * 令牌的签发属于认证域（单体保留、P7 移交 {@code mall-admin} BFF）。
 * 少一个签发入口，就少一条"某天有人在商品服务里自签一个 admin token"的路径。
 *
 * <h2>密钥</h2>
 * {@code mall.jwt.secret}：**不给源码级默认值**，缺失或短于 32 位**直接拒绝启动**
 * （与单体同一条安全加固：以前缺配置会静默用一个仓库里公开的密钥签发令牌）。
 * 必须与单体/网关**同一把**——不同密钥的表现是"后台接口一律 401"，
 * 这是最容易被误判成"拦截器写错了"的坑，见 dev/prod 的 yaml 注释。
 *
 * <p>⚠️ {@code mall.jwt.expire-hours} 在本服务**不需要**（它只影响签发时的 exp；
 * 本服务判定有效期读的是令牌自带的 {@code exp}）。
 */
@Slf4j
@Component
public class JwtVerifier {

    /** 管理端体系标记（与单体的 {@code JwtUtil.TYPE_ADMIN} 同值） */
    public static final String TYPE_ADMIN = "admin";

    private static final Pattern SUB_PATTERN = Pattern.compile("\"sub\":\\s*(\\d+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern TYP_PATTERN = Pattern.compile("\"typ\":\\s*\"([^\"]+)\"");
    private static final Pattern EXP_PATTERN = Pattern.compile("\"exp\":\\s*(\\d+)");
    /** 令牌版本号（登出/改密/禁用后 +1）——本服务**只读出来、不校验**（见类注释与拦截器注释） */
    private static final Pattern VER_PATTERN = Pattern.compile("\"ver\":\\s*(\\d+)");

    private final String secret;

    /**
     * 签发方的有效期口径（小时）——**只读出来打印，不参与校验**。
     *
     * <p>为什么要有它：主 agent 要求配置项与单体/各服务对齐（{@code mall.jwt.expire-hours}）。
     * 但本服务**只验签、不签发**（签发属认证域），有效期判定读的是**令牌自带的 {@code exp}**，
     * 所以这个值在这里**不参与任何判断**；把它读出来打一条日志，是为了运维排查时能一眼对照
     * "本服务的配置文件里有这个键、值与单体一致"，而不是留一个谁也没读的死配置。
     */
    private final long expireHours;

    public JwtVerifier(@Value("${mall.jwt.secret:}") String secret,
                       @Value("${mall.jwt.expire-hours:24}") long expireHours) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "mall.jwt.secret 未配置：请在 application-dev.yaml 或环境变量 MALL_JWT_SECRET 中提供至少 32 位随机串"
                            + "（必须与单体/网关同一把，否则后台接口会一律 401）");
        }
        if (secret.trim().length() < 32) {
            throw new IllegalStateException("mall.jwt.secret 过短(至少 32 位)，请换成随机长串");
        }
        this.secret = secret.trim();
        this.expireHours = expireHours;
        log.info("管理端 JWT 验签器就绪：secret 长度={}，expire-hours={}（**只验签、不签发**；"
                        + "有效期判定用令牌自带的 exp，本值仅用于与单体/各服务配置对齐）",
                this.secret.length(), this.expireHours);
    }

    /**
     * 解析并校验令牌（签名 + 有效期 + 三段结构），非法/过期抛 401。
     *
     * <p>⚠️ 本方法**只做密码学与时间校验**，不做"这个管理员还存在吗/被禁用了吗"这类业务校验
     * —— 那需要读 {@code sys_user}，属认证域（见 {@link AdminAuthInterceptor} 的类注释）。
     */
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

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static BusinessException unauthorized() {
        return new BusinessException(401, "登录已失效，请重新登录");
    }

    /** 解析结果（与单体的 claims 同名同序；{@code ver} 读出来但不校验） */
    public record Claims(Long userId, String username, String type, long ver) {
    }
}
