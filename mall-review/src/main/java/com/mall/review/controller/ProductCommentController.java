package com.mall.review.controller;

import com.mall.review.dto.CommentsResult;
import com.mall.review.service.CommentService;
import com.mall.review.support.ApiResponse;
import com.mall.review.support.PageQuery;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品详情页的评价读取：{@code GET /api/product/{spuId}/comments}（公开，游客可用）。
 *
 * <h2>为什么单独一个 Controller，而不是塞进 CommentController</h2>
 * 这条路径属于"前台商品浏览"（改造前住在单体的 {@code ProductPortalController}，与
 * {@code /api/category/tree}、{@code /api/product/page} 等商品接口在一起），
 * 而 {@code CommentController} 的类级 {@code @RequestMapping} 是 {@code /api/comment}——
 * 硬塞进去就得写一个绝对路径的方法映射，让"这个类的前缀"名不副实。
 * 单独一个类，路径写全，读起来就是"评价域对外提供的第二个入口"。
 *
 * <h2>它与 {@code /api/comment/product/{spuId}} 是同一条实现</h2>
 * 两条路径都调 {@link CommentService#productComments}——改造前是同一个实现
 * （{@code ProductPortalServiceImpl.comments}）被两个 Controller 各调一次，
 * 因此**必须同一次切换**：只切一条会让同一个商品页的"评价数/好评率"出现两个来源
 * （单体库与 review 库各一份副本），这是 R4 明确点名的风险。
 *
 * <p><b>网关侧必须精确匹配这条路径</b>：商品域的其它路径（{@code /api/product/page}、
 * {@code /api/product/brands}、{@code /api/product/{spuId}} 详情）整体仍属于商品域（单体），
 * 只有 {@code /api/product/{spuId}/comments} 这一种子路径改投本服务，见 mall-gateway 的路由表。
 * 路由写成"前缀通配"会把商品详情/列表一起劫走——那是 P4 明确要求避开的。
 */
@Tag(name = "前台-商品评价")
@RestController
@RequiredArgsConstructor
public class ProductCommentController {

    private final CommentService commentService;

    @GetMapping("/api/product/{spuId}/comments")
    public ApiResponse<CommentsResult> comments(
            @Parameter(description = "商品 SPU ID", example = "1001") @PathVariable Long spuId,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(commentService.productComments(spuId, page.getPageNum(), page.getPageSize()));
    }
}
