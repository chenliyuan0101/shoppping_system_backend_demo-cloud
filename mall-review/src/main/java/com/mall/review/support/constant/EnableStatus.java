package com.mall.review.support.constant;

/**
 * 「0 停用 / 1 启用」型状态列的统一定义(取值语义来自 DDL 注释与《数据库设计文档.md》，极性一致)。
 *
 * <p>本服务自持副本，只保留评价域会碰到的列：
 * <ul>
 *   <li>{@code pms_spu.status} 0下架 1上架（评价展示要判断商品是否还在架，后续批次用）</li>
 *   <li>{@code pms_sku.status} 0停用 1启用</li>
 *   <li>{@code ums_member.status} 0禁用 1正常（只在排障/只读校验时参考，
 *       身份与状态由网关与 user-center 负责，见 {@code GatewayAuthHeaders}）</li>
 * </ul>
 *
 * <p><b>以下状态列取值集合不同，不要使用本类</b>：
 * {@code pms_comment.status}(0待审核/1展示/2隐藏，见 {@link CommentStatus})、
 * {@code sms_coupon.status}(0启用/1停用，**极性相反**)。
 */
public final class EnableStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnableStatus() {
    }
}
