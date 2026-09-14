package com.mall.admin.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger UI 配置（本批只有 3 个端点，因此刻意做薄）。
 *
 * <p>界面：http://localhost:8108/swagger-ui/index.html ／ 文档：http://localhost:8108/v3/api-docs
 * <p>统一声明 JWT(Bearer) 安全方案；{@code /api/admin/auth/login} 是公开接口（其余 `/api/admin/**` 需登录）。
 * <p>⚠️ 登录示例里**不写真实口令** —— 文档页面里出现可直接提交的后台口令，等于把默认账号公开出去。
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI mallAdminOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("商城系统 管理端 BFF API（mall-admin）")
                        .description("管理员登录态（/api/admin/auth/**）。错误码见《接口文档.md》1.4。"
                                + "看板与会员管理由 P7 后续批次交付。")
                        .version("v1.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("后台登录返回的 token（typ=admin）")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /** 登录请求体示例：可直接提交（口令留占位符） */
    @Bean
    public OpenApiCustomizer loginExampleCustomizer() {
        return openApi -> {
            PathItem item = openApi.getPaths() == null ? null : openApi.getPaths().get("/api/admin/auth/login");
            if (item == null) {
                return;
            }
            Operation op = item.getPost();
            if (op == null || op.getRequestBody() == null || op.getRequestBody().getContent() == null) {
                return;
            }
            Content content = op.getRequestBody().getContent();
            MediaType mediaType = content.get("application/json");
            if (mediaType == null) {
                return;
            }
            mediaType.addExamples("默认示例", new Example().summary("可直接提交").value("""
                    {
                      "username": "admin",
                      "password": "<你的后台口令>"
                    }
                    """));
        };
    }
}
