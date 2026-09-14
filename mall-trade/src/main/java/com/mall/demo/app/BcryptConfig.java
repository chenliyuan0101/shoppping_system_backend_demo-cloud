package com.mall.demo.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器：仅引入 spring-security-crypto 做 BCrypt，
 * 不启用 Spring Security 过滤器链(全站走自定义 Token 校验)。
 */
@Configuration
public class BcryptConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
