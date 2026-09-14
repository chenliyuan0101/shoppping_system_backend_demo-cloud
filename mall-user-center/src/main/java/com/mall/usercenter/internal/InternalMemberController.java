package com.mall.usercenter.internal;

import com.mall.usercenter.service.MemberStatusService;
import com.mall.usercenter.support.ApiResponse;
import com.mall.usercenter.support.dto.MemberStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员相关的内部接口（服务间调用，{@code /internal/**} 由 {@code InternalApiAuthInterceptor} 守）。
 *
 * <p>P3-1 只开放两个只读端点，用来证明"服务能连上 {@code mall_user} 并读到真实数据"：
 * <ul>
 *   <li>{@code /count}：与单体现有的 {@code /internal/v1/user/member/count} 同名同义，
 *       P3-4 切换调用方时把单体的那一份删掉即可；</li>
 *   <li>{@code /{id}/status}：§4.4 ② 的成员状态来源（{@code {memberId, nickname, status}}）。</li>
 * </ul>
 *
 * <p>**为什么现在就把端点做成与单体同名的形状**：契约平移比"先造一个新名字、以后再改调用方"便宜得多，
 * 而且切换那天只改客户端地址、不改任何语义。
 */
@RestController
@RequestMapping("/internal/v1/user/member")
@RequiredArgsConstructor
public class InternalMemberController {

    private final MemberStatusService memberStatusService;

    @GetMapping("/count")
    public ApiResponse<Long> count() {
        return ApiResponse.ok(memberStatusService.count());
    }

    /** 会员不存在（含已逻辑删除）→ {@code data: null}：调用方据此写"不存在"的负缓存 */
    @GetMapping("/{id}/status")
    public ApiResponse<MemberStatusVO> status(@PathVariable long id) {
        return ApiResponse.ok(memberStatusService.status(id));
    }
}
