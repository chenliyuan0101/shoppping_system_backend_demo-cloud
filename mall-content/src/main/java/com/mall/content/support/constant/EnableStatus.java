package com.mall.content.support.constant;

/**
 * 「0 停用 / 1 启用」型状态列的统一定义(取值语义来自 DDL 注释与《数据库设计文档.md》，极性一致)。
 *
 * <p>覆盖列：
 * <ul>
 *   <li>{@code ums_member.status} 0禁用 1正常</li>
 *   <li>{@code sys_user.status} 0禁用 1正常(后台管理员)</li>
 *   <li>{@code pms_category.status} 0停用 1启用</li>
 *   <li>{@code pms_brand.status} 0停用 1启用</li>
 *   <li>{@code pms_spu.status} 0下架 1上架</li>
 *   <li>{@code pms_sku.status} 0停用 1启用</li>
 *   <li>{@code cms_banner.status} 0停用 1启用</li>
 *   <li>{@code cms_notice.status} 0停用 1启用</li>
 * </ul>
 *
 * <p><b>以下状态列取值集合不同，不要使用本类</b>：
 * {@code pms_comment.status}(0待审核/1展示/2隐藏，见 {@link com.mall.content.pms.support.CommentStatus})、
 * {@code sms_coupon.status}(0启用/1停用，**极性相反**，见 {@link com.mall.content.sms.support.CouponStatus})。
 */
public final class EnableStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnableStatus() {
    }
}
