package com.mall.product.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 请求对象校验器：把 DTO 上的 Bean Validation 约束跑一遍，失败时抛
 * {@link BusinessException}(400, 原中文提示)。
 *
 * <p><b>为什么需要一个包装器</b>：本项目的接口约定是"HTTP 恒 200 + 业务 code"，
 * 校验失败必须返回 {@code code=400} 与原本的中文提示；而 {@code @Valid} 抛出的
 * {@code MethodArgumentNotValidException} 只能在 Controller 层被拦截，**Service 层直接调用
 * (内部调用、单元/集成测试)时完全不生效**。所以约束写在 DTO 上，由 Service 在方法开头
 * 调 {@link #check(Object)} 触发，HTTP 与内部调用两条路径得到完全一致的结果。
 *
 * <p><b>报错顺序</b>：历史上是"从上到下逐个 if"，第一条不满足就返回。多字段同时非法时
 * 为了让提示与改造前一致，这里按 ①DTO 字段声明顺序 → ②同字段内注解优先级
 * （非空 → 长度 → 范围 → 格式）排序后取第一条。
 *
 * <pre>{@code
 * public String createOrder(Long memberId, OrderCreateRequest request) {
 *     requestValidator.check(request);   // 替代原来的一串 if (... == null) throw new BusinessException(400, "...")
 *     ...
 * }</pre>
 */
@Component
@RequiredArgsConstructor
public class RequestValidator {

    private final Validator validator;

    /** 校验失败抛 {@link BusinessException}(400, message)，message 取自约束注解上写好的原中文提示 */
    public void check(Object target) {
        if (target == null) {
            return;
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(target);
        if (!violations.isEmpty()) {
            throw new BusinessException(400, firstMessage(target.getClass(), violations));
        }
    }

    private static String firstMessage(Class<?> type, Set<ConstraintViolation<Object>> violations) {
        List<ConstraintViolation<Object>> sorted = new ArrayList<>(violations);
        sorted.sort(Comparator
                .comparingInt((ConstraintViolation<Object> v) -> fieldIndex(type, v))
                .thenComparingInt(RequestValidator::annotationRank));
        return sorted.get(0).getMessage();
    }

    /** DTO 字段声明顺序(决定跨字段的报错先后) */
    private static int fieldIndex(Class<?> type, ConstraintViolation<?> violation) {
        String root = rootField(violation);
        int index = 0;
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(root)) {
                return index;
            }
            index++;
        }
        return Integer.MAX_VALUE;
    }

    /** 取属性路径的根字段名：{@code "receiverName"} / {@code "skus[0].price"} → 对应根字段 */
    private static String rootField(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.' || c == '[') {
                return path.substring(0, i);
            }
        }
        return path;
    }

    /** 同一字段上多个约束同时失败时的顺序：非空 → 长度 → 范围 → 格式（与历史 if 的书写顺序一致） */
    private static int annotationRank(ConstraintViolation<?> violation) {
        String name = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return switch (name) {
            case "NotNull", "NotBlank", "NotEmpty" -> 0;
            case "Size" -> 1;
            case "Min", "Max", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                 "DecimalMin", "DecimalMax", "Digits" -> 2;
            case "Pattern", "Email" -> 3;
            default -> 4;
        };
    }
}
