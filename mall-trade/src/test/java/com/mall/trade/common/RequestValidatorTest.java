package com.mall.trade.common;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestValidator} 单元测试：不启动 Spring，直接校验"抛 BusinessException(400, 原提示)"与
 * **报错顺序**（多字段非法时报哪一条）。
 *
 * <p>为什么单独测它：接口约定是"HTTP 恒 200 + code=400 + 原中文提示"，一旦顺序变了，
 * 同一份非法请求返回的 message 就会变，前端提示与《接口文档.md》都会对不上。
 */
class RequestValidatorTest {

    private static ValidatorFactory factory;
    private static RequestValidator requestValidator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        Validator validator = factory.getValidator();
        requestValidator = new RequestValidator(validator);
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** 字段声明顺序 = 报错先后顺序（username 在第 1 位） */
    static class RegisterLike {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 20, message = "用户名长度为4~20位")
        private String username;

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{8,20}$", message = "密码须为8~20位且含字母与数字")
        private String password;

        @Min(value = 1, message = "数量至少为 1")
        private Integer quantity;

        public void setUsername(String username) {
            this.username = username;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }
    }

    @Test
    @DisplayName("多个字段同时非法 → 报声明在前的字段（与历史\"从上到下逐个 if\"一致）")
    void reportsFirstFieldInDeclarationOrder() {
        RegisterLike req = new RegisterLike();
        req.setUsername("");
        req.setPassword("");

        assertThatThrownBy(() -> requestValidator.check(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("用户名不能为空");
                });
    }

    @Test
    @DisplayName("同字段多条约束 → 非空 > 长度 > 范围 > 格式")
    void reportsInAnnotationPriorityOrder() {
        // 空串同时违反 @NotBlank 与 @Size(min=4)：按优先级应报"非空"
        RegisterLike blank = new RegisterLike();
        blank.setUsername("");
        blank.setPassword("Abc123456");
        assertThatThrownBy(() -> requestValidator.check(blank))
                .hasMessage("用户名不能为空");

        // 非空通过、长度不满足 → 报长度
        RegisterLike tooShort = new RegisterLike();
        tooShort.setUsername("ab");
        tooShort.setPassword("Abc123456");
        assertThatThrownBy(() -> requestValidator.check(tooShort))
                .hasMessage("用户名长度为4~20位");

        // 非空通过、只有 @Pattern 违反 → 报格式
        RegisterLike badPassword = new RegisterLike();
        badPassword.setUsername("alice2026");
        badPassword.setPassword("短");
        assertThatThrownBy(() -> requestValidator.check(badPassword))
                .hasMessage("密码须为8~20位且含字母与数字");
    }

    @Test
    @DisplayName("只有一个字段非法 → 直接报该字段，code=400")
    void reportsSingleViolation() {
        RegisterLike req = new RegisterLike();
        req.setUsername("alice2026");
        req.setPassword("Abc123456");
        req.setQuantity(0);

        assertThatThrownBy(() -> requestValidator.check(req))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(400);
                    assertThat(e.getMessage()).isEqualTo("数量至少为 1");
                });
    }

    @Test
    @DisplayName("选填字段为 null → 不触发 @Size/@Min/@Pattern（与\"只在给了值时才校验\"等价）")
    void nullOptionalFieldPasses() {
        RegisterLike req = new RegisterLike();
        req.setUsername("alice2026");
        req.setPassword("Abc123456");

        assertThatCode(() -> requestValidator.check(req)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("target 为 null → 直接放行")
    void nullTargetPasses() {
        assertThatCode(() -> requestValidator.check(null)).doesNotThrowAnyException();
    }

    /** 非空判断用 @NotNull 的场景（对象/数值型必填字段） */
    static class RequireIdOnly {
        @NotNull(message = "收货地址不能为空")
        private Long addressId;

        public void setAddressId(Long addressId) {
            this.addressId = addressId;
        }
    }

    @Test
    @DisplayName("@NotNull 缺失 → 400 + 原提示")
    void notNullViolation() {
        assertThatThrownBy(() -> requestValidator.check(new RequireIdOnly()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("收货地址不能为空");
    }
}
