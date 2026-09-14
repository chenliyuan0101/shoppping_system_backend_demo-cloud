package com.mall.admin.support.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 启停请求体：{@code {"status":0|1}}（自持副本，逐字对齐单体 {@code com.mall.demo.common.dto.StatusRequest}）。
 *
 * <p>用于 {@code PUT /api/admin/member/{id}/status}。字段名必须逐字一致：
 * 单体侧 {@code @RequestBody StatusRequest {status}}，前端 {@code MemberList.vue} 发的就是这个体。
 * 少了字段 ⇒ {@code status} 为 null ⇒ 400「状态值仅支持 0禁用 1正常」（C1 文案，见 AdminMemberServiceImpl）。
 */
@Data
@Schema(description = "启停请求")
public class StatusRequest {

    @Schema(description = "状态 0禁用 1正常", example = "1")
    private Integer status;
}
