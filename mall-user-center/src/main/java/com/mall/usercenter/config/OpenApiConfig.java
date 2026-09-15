package com.mall.usercenter.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import com.mall.common.support.MemberId;

/**
 * OpenAPI / Swagger UI 配置。
 * 界面：http://localhost:8080/swagger-ui/index.html
 * 文档：http://localhost:8080/v3/api-docs
 *
 * 说明1：统一声明 JWT(Bearer) 安全方案，Swagger UI 右上角 Authorize 填登录返回的 token；
 * /api/auth/register、/login、/api/admin/auth/login 为公开接口。
 * 说明2：本环境对部分注解的 schema 注解读取不稳定，为保证 RapiDoc/Swagger UI 的 Try-it
 * 有可直接提交的示例，用 OpenApiCustomizer 在生成模型上统一注入 参数/请求体 example。
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /** 可直接提交的商品新增/更新请求体示例 */
    private static final String PRODUCT_BODY_EXAMPLE = """
            {
              "categoryId": 12,
              "title": "无线降噪耳机 Pro",
              "subtitle": "主动降噪 · 30小时续航",
              "mainImage": "http://localhost:9000/mall/seed/earphone-pro.jpg",
              "description": "示例商品描述",
              "detailHtml": "<p>图文详情</p>",
              "images": ["http://localhost:9000/mall/seed/earphone-pro.jpg"],
              "params": [{"name": "续航", "value": "30小时"}],
              "skus": [
                {"skuCode": "EP-BK", "specValues": [{"name": "颜色", "value": "黑"}],
                 "image": "http://localhost:9000/mall/seed/earphone-bk.jpg",
                 "price": 29900, "originalPrice": 39900, "stock": 50}
              ]
            }
            """;

    private static final String STATUS_BODY_EXAMPLE = """
            { "status": 1 }
            """;

    /**
     * {@code @MemberId} / {@code @AuthToken} 由参数解析器从请求头注入，不是真正的入参；
     * springdoc 会把它们当成同名 query 参数渲染出来，这里统一摘掉——Token 的填写入口是右上角
     * Authorize（见 {@link #SECURITY_SCHEME_NAME}）。
     */
    private static final Set<String> INJECTED_PARAM_NAMES = Set.of("memberId", "token");

    @Bean
    public OpenAPI mallOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("商城系统 API")
                        .description("注册登录 / 后台登录 / 后台类目 / 后台商品管理接口。"
                                + "错误码见《接口文档.md》1.4。")
                        .version("v1.1"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录/注册接口返回的 token")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * 程序化补充接口示例（参数 example + 请求体 example），保证调试页面可直接点发送。
     */
    @Bean
    public OpenApiCustomizer exampleCustomizer() {
        return openApi -> {
            // path 参数示例(便于 UI 自动填充)
            paramExample(openApi, "/api/admin/product/{spuId}", PathItem.HttpMethod.GET, "spuId", 1001L);
            paramExample(openApi, "/api/admin/product/{spuId}", PathItem.HttpMethod.PUT, "spuId", 1001L);
            paramExample(openApi, "/api/admin/product/{spuId}/status", PathItem.HttpMethod.PUT, "spuId", 1001L);
            paramExample(openApi, "/api/admin/product/{spuId}", PathItem.HttpMethod.DELETE, "spuId", 1001L);
            paramExample(openApi, "/api/admin/category/{id}", PathItem.HttpMethod.PUT, "id", 11L);
            paramExample(openApi, "/api/admin/category/{id}/status", PathItem.HttpMethod.PUT, "id", 11L);
            paramExample(openApi, "/api/admin/category/{id}", PathItem.HttpMethod.DELETE, "id", 11L);

            // 商品分页查询参数示例
            Map<String, Object> pageParams = new LinkedHashMap<>();
            pageParams.put("keyword", "耳机");
            pageParams.put("categoryId", 12L);
            pageParams.put("status", 1);
            pageParams.put("pageNum", 1L);
            pageParams.put("pageSize", 10L);
            paramExamples(openApi, "/api/admin/product/page", PathItem.HttpMethod.GET, pageParams);

            // 请求体示例
            bodyExample(openApi, "/api/admin/product", PathItem.HttpMethod.POST, PRODUCT_BODY_EXAMPLE);
            bodyExample(openApi, "/api/admin/product/{spuId}", PathItem.HttpMethod.PUT, PRODUCT_BODY_EXAMPLE);
            bodyExample(openApi, "/api/admin/category", PathItem.HttpMethod.POST,
                    "{\n  \"parentId\": 0,\n  \"name\": \"家用电器\",\n  \"sort\": 3\n}");
            bodyExample(openApi, "/api/admin/product/{spuId}/status", PathItem.HttpMethod.PUT, STATUS_BODY_EXAMPLE);
            bodyExample(openApi, "/api/admin/category/{id}/status", PathItem.HttpMethod.PUT, STATUS_BODY_EXAMPLE);
            // 登录示例不写真实口令：文档页面里出现可直接提交的后台口令 = 把默认账号公开
            bodyExample(openApi, "/api/admin/auth/login", PathItem.HttpMethod.POST,
                    "{\n  \"username\": \"admin\",\n  \"password\": \"<你的后台口令>\"\n}");
        };
    }

    /**
     * 摘掉 {@code @MemberId} / {@code @AuthToken} 渲染出的伪 query 参数（见 {@link #INJECTED_PARAM_NAMES}）。
     */
    @Bean
    public OperationCustomizer injectedParamCustomizer() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() != null) {
                operation.getParameters().removeIf(p -> "query".equals(p.getIn())
                        && p.getName() != null && INJECTED_PARAM_NAMES.contains(p.getName()));
            }
            return operation;
        };
    }

    private static void paramExample(OpenAPI openApi, String path, PathItem.HttpMethod method,
                                     String name, Object example) {
        Operation op = operation(openApi, path, method);
        if (op == null || op.getParameters() == null) {
            return;
        }
        for (Parameter p : op.getParameters()) {
            if (name.equals(p.getName())) {
                p.setExample(example);
            }
        }
    }

    private static void paramExamples(OpenAPI openApi, String path, PathItem.HttpMethod method,
                                      Map<String, Object> examples) {
        Operation op = operation(openApi, path, method);
        if (op == null || op.getParameters() == null) {
            return;
        }
        for (Parameter p : op.getParameters()) {
            if (examples.containsKey(p.getName())) {
                p.setExample(examples.get(p.getName()));
            }
        }
    }

    private static void bodyExample(OpenAPI openApi, String path, PathItem.HttpMethod method, String json) {
        Operation op = operation(openApi, path, method);
        if (op == null || op.getRequestBody() == null || op.getRequestBody().getContent() == null) {
            return;
        }
        Content content = op.getRequestBody().getContent();
        if (content.get("application/json") == null) {
            return;
        }
        MediaType mediaType = content.get("application/json");
        mediaType.addExamples("默认示例",
                new Example().summary("可直接提交").value(json));
    }

    private static Operation operation(OpenAPI openApi, String path, PathItem.HttpMethod method) {
        PathItem item = openApi.getPaths().get(path);
        if (item == null) {
            return null;
        }
        return switch (method) {
            case GET -> item.getGet();
            case POST -> item.getPost();
            case PUT -> item.getPut();
            case DELETE -> item.getDelete();
            default -> null;
        };
    }
}
