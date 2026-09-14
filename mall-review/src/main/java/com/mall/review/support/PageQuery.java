package com.mall.review.support;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 列表接口的统一分页入参（{@code pageNum}/{@code pageSize}）。
 *
 * <p>只承载"默认值与文档"，不做钳制：每页上限各接口并不相同，
 * 钳制统一由 Service 侧 {@link PageKit#size(long, long)} 处理。
 * Controller 侧写 {@code @ParameterObject PageQuery page}，springdoc 会把两个字段
 * 展开成同名的 query 参数，**对外契约不变**。
 */
@Data
@Schema(description = "分页参数(不传则 pageNum=1、pageSize=10)")
public class PageQuery {

    @Schema(description = "页码", example = "1", defaultValue = "1")
    private long pageNum = 1;

    @Schema(description = "每页条数(各接口上限不同，最大 100；超出按该接口上限处理)", example = "10", defaultValue = "10")
    private long pageSize = 10;
}
