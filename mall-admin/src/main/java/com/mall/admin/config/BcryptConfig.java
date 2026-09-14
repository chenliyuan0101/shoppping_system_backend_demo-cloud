package com.mall.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器：仅引入 spring-security-crypto 做 BCrypt，
 * 不启用 Spring Security 过滤器链（全站走自定义 Token 校验）。
 *
 * <p>⚠️ 必须与 {@code sys_user.password} 里已有的 BCrypt 密文（{@code $2a$10$…}）兼容：
 * {@link BCryptPasswordEncoder} 默认强度 10，与 seed 数据一致。
 * 迁移过来的行**不是**重新加密的（迁移只搬行，不改密码字段）—— 换编码器会让所有人都登不上后台。
 */
@Configuration
public class BcryptConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
