package com.mall.product.support;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用调试日志切面：
 * 对 controller / service / task 的所有方法打印 调用进出 + 参数摘要 + 耗时(debug 级别)。
 * 开启：application-dev.yaml logging.level.com.mall.product=debug（默认已开）。
 */
@Aspect
@Component
@Slf4j
public class MethodLogAspect {

    private static final int MAX_ARGS = 300;

    @Around("execution(* com.mall.product..controller..*(..))"
            + " || execution(* com.mall.product..service..*(..))"
            + " || execution(* com.mall.product..task..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        // 登录/注册/改密等敏感方法不打参数，避免密码明文进日志
        boolean sensitive = name.toLowerCase().contains("login")
                || name.toLowerCase().contains("password")
                || name.toLowerCase().contains("register");
        // 先判级别再拼参数：briefArgs 会把实参整体 JSON 序列化（大对象如 detailHtml 很贵），
        // 生产是 info 级、debug 关闭，以前等于每次调用都白做一次序列化
        boolean debugOn = log.isDebugEnabled();
        if (debugOn) {
            log.debug(">> 调用 {} args={}", name, sensitive ? "[隐去敏感参数]"
                    : briefArgs(pjp.getArgs()));
        }
        try {
            Object result = pjp.proceed();
            if (debugOn) {
                log.debug("<< 结束 {} 耗时 {}ms", name, System.currentTimeMillis() - start);
            }
            return result;
        } catch (Throwable t) {
            if (debugOn) {
                log.debug("!! 异常 {} 耗时 {}ms -> {}: {}", name, System.currentTimeMillis() - start,
                        t.getClass().getSimpleName(), t.getMessage());
            }
            throw t;
        }
    }

    /** 参数摘要：标量直接打印，对象转 JSON，超长截断 */
    private static String briefArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        for (Object arg : args) {
            if (arg == null) {
                parts.add("null");
            } else if (arg instanceof CharSequence cs) {
                parts.add(looksLikeSecret(cs.toString()) ? "[隐去凭据]" : String.valueOf(arg));
            } else if (arg instanceof Number || arg instanceof Boolean) {
                parts.add(String.valueOf(arg));
            } else {
                try {
                    String json = JsonKit.toJson(arg);
                    parts.add(json.length() > MAX_ARGS ? json.substring(0, MAX_ARGS) + "..." : json);
                } catch (Exception e) {
                    parts.add(arg.getClass().getSimpleName());
                }
            }
        }
        return "[" + String.join(", ", parts) + "]";
    }

    /**
     * 判断字符串参数是否像凭据（Authorization 头/JWT）。
     *
     * <p>为什么按"形状"而不是按方法名：`/api/auth/me`、`/api/order/**` 这些方法名里没有
     * login/password 关键字，但它们的入参就是一串完整可用的 token（有效期 24h），
     * 以前的实现会把 token 原文写进日志。
     */
    private static boolean looksLikeSecret(String value) {
        if (value.startsWith("Bearer ") || value.startsWith("Basic ")) {
            return true;
        }
        // JWT 形状：三段 base64url，用两个点分隔，且长度可观
        return value.length() > 40 && value.chars().filter(c -> c == '.').count() == 2;
    }
}
