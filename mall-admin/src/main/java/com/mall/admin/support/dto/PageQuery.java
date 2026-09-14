package com.mall.admin.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 列表接口的统一分页入参（自持副本，**逐字对齐单体 {@code com.mall.demo.common.PageQuery}**）。
 *
 * <p>为什么必须逐字对齐：{@code GET /api/admin/member/page} 的 {@code pageNum}/{@code pageSize}
 * 默认值（1 / 10）参与"转发给 user-center 的请求体"，而 user-center 侧只对**非法值**做钳制。
 * 默认值不同 ⇒ 不带分页参数的请求会取到不同的页（C1 会红，而且是那种"看起来接口没错"的漂移）。
 *
 * <p>与单体一致：<b>这里只承载默认值与文档，不做钳制</b>；钳制由属主域（user-center 的 {@code PageKit}）
 * 统一处理——分页口径只有一个属主。
 */
@Data
@Schema(description = "分页参数(不传则 pageNum=1、pageSize=10)")
public class PageQuery {

    @Schema(description = "页码", example = "1", defaultValue = "1")
    private long pageNum = 1;

    @Schema(description = "每页条数(各接口上限不同，最大 100；超出按该接口上限处理)", example = "10", defaultValue = "10")
    private long pageSize = 10;
}
