package com.mall.content.support;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 列表接口的统一分页入参。
 *
 * <p>为什么要有它：原来每个列表方法都要重复两行同样的注解
 * （{@code @Parameter(description = "页码", example = "1") @RequestParam(defaultValue = "1") long pageNum}
 * 与"每页条数"那份），18 个接口 × 2 = 36 处样板，而且默认值与文档文案散在各处、改一处就漏一处。
 * 现在统一由本类承载：Controller 写 {@code @ParameterObject PageQuery page}，
 * springdoc 会把两个字段展开成同名的 {@code pageNum}/{@code pageSize} query 参数，**对外契约不变**。
 *
 * <p>用法：
 * <pre>{@code
 * @GetMapping("/page")
 * public ApiResponse<PageResult<OrderListVO>> page(@MemberId Long memberId,
 *                                                  @ParameterObject PageQuery page,
 *                                                  @RequestParam(required = false) Integer status) {
 *     return ApiResponse.ok(orderService.page(memberId, status, null, page.getPageNum(), page.getPageSize()));
 * }
 * }</pre>
 *
 * <p><b>这里只承载"默认值与文档"，不做钳制</b>：每页上限各接口并不相同（商品/品牌 100、看板 20/30/90、
 * 其余 50），钳制仍由 Service 侧 {@link PageKit#size(long, long)} 统一处理。
 */
@Data
@Schema(description = "分页参数(不传则 pageNum=1、pageSize=10)")
public class PageQuery {

    @Schema(description = "页码", example = "1", defaultValue = "1")
    private long pageNum = 1;

    @Schema(description = "每页条数(各接口上限不同，最大 100；超出按该接口上限处理)", example = "10", defaultValue = "10")
    private long pageSize = 10;
}
