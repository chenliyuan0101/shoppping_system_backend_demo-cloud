package com.mall.search.support.constant;

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
 * 券模板的 status 列(0启用/1停用，**极性相反**)——该列已随券（P5 步骤 C）搬到
 * {@code mall-marketing}，常量类在 {@code com.mall.marketing.support.constant.CouponStatus}；
 * 单体侧**不再保留副本**（单体已经没有券表的读写，留副本只会让人以为还能读）。
 *
 * <p><b>P4 批次 3</b>：原来这里还列着 {@code pms_comment.status}(0待审核/1展示/2隐藏)——评价表已随
 * 评价域搬去 {@code mall-review}，那个三态常量类也一起搬走了（现在住在
 * {@code com.mall.review.support.constant.CommentStatus}）。单体里不再有它的副本，
 * 因为单体里不再有任何读写评价表的代码。
 */
public final class EnableStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnableStatus() {
    }
}
