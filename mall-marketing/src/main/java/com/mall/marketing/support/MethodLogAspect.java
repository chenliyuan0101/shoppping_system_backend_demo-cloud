package com.mall.marketing.support;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法调用调试日志切面：
 * 对 controller / service 的所有方法打印 调用进出 + 参数摘要 + 耗时(debug 级别)。
 * 开启：application-dev.yaml logging.level.com.mall.marketing=debug（默认已开）。
 *
 * <p>切点根包从 {@code com.mall.review} 换成 {@code com.mall.marketing}——
 * 这类切点写错包名的表现是"日志静默消失"，是拆服务时最容易漏改的一处。
 *
 * <p>⚠️ 券的排障几乎全靠这些日志：lock/use/unlock 的成败只体现在
 * "哪一次调用返回了 changed=false"，而影响行数不会自己说话。
 */
@Aspect
@Component
@Slf4j
public class MethodLogAspect {

    private static final int MAX_ARGS = 300;

    @Around("execution(* com.mall.marketing..controller..*(..))"
            + " || execution(* com.mall.marketing..service..*(..))"
            + " || execution(* com.mall.marketing..task..*(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        long start = System.currentTimeMillis();
        // 敏感方法不打参数，避免密码/令牌明文进日志
        boolean sensitive = name.toLowerCase().contains("login")
                || name.toLowerCase().contains("password")
                || name.toLowerCase().contains("register");
        // 先判级别再拼参数：briefArgs 会把实参整体 JSON 序列化，生产是 info 级、debug 关闭
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

    /** 按"形状"判断字符串参数是否像凭据（Authorization 头/JWT），而不是按方法名 */
    private static boolean looksLikeSecret(String value) {
        if (value.startsWith("Bearer ") || value.startsWith("Basic ")) {
            return true;
        }
        // JWT 形状：三段 base64url，用两个点分隔，且长度可观
        return value.length() > 40 && value.chars().filter(c -> c == '.').count() == 2;
    }
}
