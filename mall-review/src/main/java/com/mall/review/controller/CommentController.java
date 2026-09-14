package com.mall.review.controller;

import com.mall.review.dto.CommentsResult;
import com.mall.review.dto.MineCommentVO;
import com.mall.review.dto.SubmitCommentRequest;
import com.mall.review.service.CommentService;
import com.mall.review.support.ApiResponse;
import com.mall.review.support.MemberId;
import com.mall.review.support.PageQuery;
import com.mall.review.support.PageResult;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价 /api/comment(见《接口文档.md》2.8)：发表需登录；商品评价公开；我的评价需登录。
 *
 * <p><b>逐字副本</b>（单体 {@code com.mall.demo.pms.controller.CommentController}）：
 * 路径、HTTP 方法、参数名、响应类型全部不变，前端零改动（R4：两条读路径必须同批切换，
 * 所以 {@code /api/product/{spuId}/comments} 与本类的 {@code /api/comment/product/{spuId}}
 * 在同一个提交里一起搬到了本服务，见 {@link ProductCommentController}）。
 *
 * <p><b>身份从哪来</b>：{@code @MemberId} 由 {@code GatewayIdentityResolver} 从网关注入的
 * {@code X-Gateway-Auth} + {@code X-Member-Id} 解析（本服务不验签、不接触令牌，
 * 见 {@code config/WebConfig}）。解析不到一律 {@code 401「未登录」}——文案与改造前一致。
 * 注意提交评价接口<b>不</b>从请求体里收 memberId：那等于让客户端自报身份。
 */
@Tag(name = "用户-评价")
@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @PostMapping
    public ApiResponse<Void> submit(@MemberId Long memberId, @RequestBody SubmitCommentRequest request) {
        commentService.submit(memberId, request);
        return ApiResponse.ok();
    }

    @GetMapping("/product/{spuId}")
    public ApiResponse<CommentsResult> productComments(@Parameter(description = "商品 SPU ID", example = "1001")
                                                       @PathVariable Long spuId,
                                                       @ParameterObject PageQuery page) {
        return ApiResponse.ok(commentService.productComments(spuId, page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/mine")
    public ApiResponse<PageResult<MineCommentVO>> mine(@MemberId Long memberId,
                                                       @ParameterObject PageQuery page) {
        return ApiResponse.ok(commentService.mine(memberId, page.getPageNum(), page.getPageSize()));
    }
}
