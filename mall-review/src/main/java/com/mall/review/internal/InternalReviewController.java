package com.mall.review.internal;

import com.mall.review.service.CommentCountService;
import com.mall.review.support.ApiResponse;
import com.mall.review.support.dto.CommentBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价相关的内部接口（服务间调用，{@code /internal/**} 由
 * {@code InternalApiAuthInterceptor} 守：没有 {@code X-Internal-Token} 一律 403）。
 *
 * <p>P4 第 1 批只开放两个**只读**端点，用来证明"服务能连上 {@code mall_review} 并读到真实数据"：
 * <ul>
 *   <li>{@code /count}：评价总数——admin 的会员/商品聚合、trade 的对账都要这个数；</li>
 *   <li>{@code /{id}}：单条评价快照——与单体 {@code pms} 的评论查询同名同义，
 *       切换调用方时只改地址、不改语义。</li>
 * </ul>
 *
 * <p><b>为什么现在就把端点做成与单体同名的形状</b>：契约平移比"先造一个新名字、
 * 以后再改调用方"便宜得多（P3 的同一教训）。
 *
 * <p>⚠️ 路径前缀 {@code /internal/v1/review/comment} 与单体的
 * {@code /internal/v1/review/comment/**}（方案 §4.5 规划的 review 内部面）保持一致；
 * 本批不改网关，因此这些端点目前只有直连服务端口（内网）才可达——
 * 这正是它们需要共享密钥的原因。
 */
@RestController
@RequestMapping("/internal/v1/review/comment")
@RequiredArgsConstructor
public class InternalReviewController {

    private final CommentCountService commentCountService;

    /** 评价总数（不含已逻辑删除） */
    @GetMapping("/count")
    public ApiResponse<Long> count() {
        return ApiResponse.ok(commentCountService.count());
    }

    /** 单条评价快照；评价不存在（含已逻辑删除）→ {@code data: null}：调用方据此写负缓存 */
    @GetMapping("/{id}")
    public ApiResponse<CommentBriefVO> detail(@PathVariable long id) {
        return ApiResponse.ok(commentCountService.findById(id));
    }
}
