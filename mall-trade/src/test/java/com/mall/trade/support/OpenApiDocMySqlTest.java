package com.mall.trade.support;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.mall.trade.app.OpenApiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mall.common.support.MemberId;

/**
 * OpenAPI 文档回归：保证 {@code /v3/api-docs} 生成的内容与"接口怎么调"一致。
 *
 * <p>守两件事：
 * <ol>
 *   <li><b>不出现伪参数</b>：{@code @MemberId} / {@code @AuthToken} 是由
 *       {@link com.mall.trade.auth.support.MemberIdArgumentResolver} 从请求头注入的，不是真正的入参；
 *       springdoc 若把它们当成同名 query 参数渲染出来，调试页面会多出两个填了也没用的框。</li>
 *   <li><b>DTO 上的 Bean Validation 约束进入文档</b>：约束写在 DTO 字段上，
 *       Swagger/RapiDoc 才能显示长度/范围/格式，前端据此生成表单校验。</li>
 * </ol>
 *
 * <p>生产 profile 默认关闭 springdoc(见 application-prod.yaml)，这里用 `properties` 单独开启。
 */
@SpringBootTest(properties = "springdoc.api-docs.enabled=true")
@AutoConfigureMockMvc
class OpenApiDocMySqlTest extends MySqlTestBase {

    private String apiDocs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** 路径不存在时返回空列表，方便断言"不存在某类节点" */
    @SuppressWarnings("unchecked")
    private static List<Object> readOrEmpty(String json, String path) {
        try {
            return JsonPath.read(json, path);
        } catch (PathNotFoundException e) {
            return List.of();
        }
    }

    @Test
    @DisplayName("[OpenAPI] @MemberId/@AuthToken 不得渲染成 query 参数")
    void injectedParamsAreHidden() throws Exception {
        String body = apiDocs();

        assertThat(readOrEmpty(body, "$..parameters[?(@.name=='memberId')]"))
                .as("@MemberId 是从 Authorization 头解析的，不应出现在文档参数里").isEmpty();
        assertThat(readOrEmpty(body, "$..parameters[?(@.name=='token')]"))
                .as("@AuthToken 是从 Authorization 头解析的，不应出现在文档参数里").isEmpty();
    }

    @Test
    @DisplayName("[OpenAPI] Bearer 安全方案仍在（Swagger 右上角 Authorize 可用）")
    void bearerSecuritySchemeKept() throws Exception {
        String body = apiDocs();

        // 注意：JsonPath.read 是泛型方法，直接把结果塞进重载的 assertThat 会让编译器无法推断类型，
        // 因此先落到带类型的局部变量再断言。
        Object scheme = JsonPath.read(body, "$.components.securitySchemes." + OpenApiConfig.SECURITY_SCHEME_NAME);
        assertThat(scheme).isNotNull();
    }

    @Test
    @DisplayName("[OpenAPI] DTO 上的校验约束进入 schema（长度/范围/格式能被文档与前端读到）")
    void beanValidationConstraintsArePublished() throws Exception {
        String body = apiDocs();

        boolean hasLength = !readOrEmpty(body, "$.components.schemas.*.properties.*.minLength").isEmpty()
                || !readOrEmpty(body, "$.components.schemas.*.properties.*.maxLength").isEmpty();
        boolean hasRange = !readOrEmpty(body, "$.components.schemas.*.properties.*.minimum").isEmpty()
                || !readOrEmpty(body, "$.components.schemas.*.properties.*.maximum").isEmpty();
        boolean hasPattern = !readOrEmpty(body, "$.components.schemas.*.properties.*.pattern").isEmpty();

        assertThat(hasLength || hasRange || hasPattern)
                .as("请求 DTO 上至少要有一处 @Size/@Min/@Max/@Pattern 出现在 components.schemas 里").isTrue();
    }

    @Test
    @DisplayName("[OpenAPI] 分页参数必须仍是 pageNum/pageSize 两个 query 参数（PageQuery + @ParameterObject 不得改变对外契约）")
    void pageParamsStayQueryParams() throws Exception {
        String body = apiDocs();

        assertThat(readOrEmpty(body, "$..parameters[?(@.name=='pageNum' && @.in=='query')]"))
                .as("pageNum 必须仍作为 query 参数出现在文档里").isNotEmpty();
        assertThat(readOrEmpty(body, "$..parameters[?(@.name=='pageSize' && @.in=='query')]"))
                .as("pageSize 必须仍作为 query 参数出现在文档里").isNotEmpty();
    }

    @Test
    @DisplayName("[OpenAPI] 约束要落在**正确的字段**上（@Min(1) → discountAmount.minimum=1，@NotNull → required）")
    void dtoConstraintsLandOnTheRightField() throws Exception {
        String body = apiDocs();
        String base = "$.components.schemas.AdminCouponSaveRequest";

        // 范围：AdminCouponSaveRequest.discountAmount 的 @Min(1)
        Number minimum = JsonPath.read(body, base + ".properties.discountAmount.minimum");
        assertThat(minimum.intValue()).as("@Min(1) 应渲染成 minimum=1").isEqualTo(1);

        // 必填：@NotNull/@NotBlank 应把字段名放进 required
        List<String> required = JsonPath.read(body, base + ".required");
        assertThat(required).as("@NotNull/@NotBlank 的字段应进 required")
                .contains("name", "discountAmount", "thresholdAmount");
    }
}
