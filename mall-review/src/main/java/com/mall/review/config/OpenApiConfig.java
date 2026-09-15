package com.mall.review.config;

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
 * OpenAPI / Swagger UI 配置（骨架抄自 user-center / 单体的同一份）。
 * 界面：http://localhost:8104/swagger-ui/index.html
 * 文档：http://localhost:8104/v3/api-docs
 *
 * <p>说明1：统一声明 JWT(Bearer) 安全方案，Swagger UI 右上角 Authorize 填登录返回的 token；
 * 注册/登录是公开接口。
 * <p>说明2：本环境对部分注解的 schema 注解读取不稳定，为保证 RapiDoc/Swagger UI 的 Try-it
 * 有可直接提交的示例，用 OpenApiCustomizer 在生成模型上统一注入 参数/请求体 example。
 *
 * <p><b>与 user-center 那份的唯一差别</b>：路径示例换成了评价域的对外路径
 * （方案 §2.4 / §4.5：{@code GET /api/product/{spuId}/comments}、{@code POST /api/comment}、
 * {@code DELETE /api/comment/{id}}）。那些路径在 P4 后续批次才由网关路由到本服务，
 * 因此现在这里的 {@code operation(...)} 会返回 null 并**静默跳过**——
 * 这正是这些 helper 都做 null 检查的原因：示例是"锦上添花"，缺路径绝不能影响文档生成。
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "bearerAuth";

    /** 提交评价的请求体示例（可直接提交） */
    private static final String COMMENT_BODY_EXAMPLE = """
            {
              "orderItemId": 1001,
              "rating": 5,
              "content": "质量很好，物流很快",
              "images": ["http://localhost:9000/mall/seed/earphone-pro.jpg"]
            }
            """;

    /**
     * {@code @MemberId} 由参数解析器从网关注入的身份头取，不是真正的入参；
     * springdoc 会把它当成同名 query 参数渲染出来，这里统一摘掉——
     * 会员身份的填写入口是右上角 Authorize（见 {@link #SECURITY_SCHEME_NAME}），
     * 让文档上出现一个可手填的 {@code memberId} 参数等于"教人越权"。
     */
    private static final Set<String> INJECTED_PARAM_NAMES = Set.of("memberId", "token");

    @Bean
    public OpenAPI mallOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("商城系统 - 评价服务 API")
                        .description("商品评价的提交 / 展示 / 管理接口。"
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
            paramExample(openApi, "/api/product/{spuId}/comments", PathItem.HttpMethod.GET, "spuId", 1001L);
            paramExample(openApi, "/api/comment/{id}", PathItem.HttpMethod.DELETE, "id", 1L);

            // 评价列表的分页参数示例（pageNum/pageSize 由 PageQuery 展开而来）
            Map<String, Object> pageParams = new LinkedHashMap<>();
            pageParams.put("pageNum", 1L);
            pageParams.put("pageSize", 10L);
            paramExamples(openApi, "/api/product/{spuId}/comments", PathItem.HttpMethod.GET, pageParams);

            // 请求体示例（路径尚不存在时 helper 内部直接返回，不影响文档生成）
            bodyExample(openApi, "/api/comment", PathItem.HttpMethod.POST, COMMENT_BODY_EXAMPLE);
        };
    }

    /**
     * 摘掉 {@code @MemberId} 渲染出的伪 query 参数（见 {@link #INJECTED_PARAM_NAMES}）。
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
