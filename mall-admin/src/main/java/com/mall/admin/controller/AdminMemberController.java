package com.mall.admin.controller;

import com.mall.admin.dto.MemberAdminVO;
import com.mall.admin.service.AdminMemberService;
import com.mall.admin.support.ApiResponse;
import com.mall.admin.support.dto.PageQuery;
import com.mall.admin.support.dto.PageResult;
import com.mall.admin.support.dto.StatusRequest;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 后台会员管理 {@code /api/admin/member}（见《接口文档.md》3.7，需管理员登录）。
 *
 * <h2>C1：与单体 {@code com.mall.demo.admin.controller.AdminMemberController} 逐字相同</h2>
 * <pre>
 *   GET /api/admin/member/page?keyword=&amp;status=&amp;createDateStart=&amp;createDateEnd=&amp;pageNum=&amp;pageSize=
 *   GET /api/admin/member/{id}
 *   PUT /api/admin/member/{id}/status          body {"status":0|1}
 * </pre>
 * 逐字对齐的两处细节（都容易在重写时"顺手改掉"）：
 * <ol>
 *   <li>日期参数是 {@code yyyy-MM-dd} 的 {@code LocalDate}，转成 {@code LocalDateTime} 的方式与单体一致：
 *       <b>起 = 当天 00:00（含）</b>、<b>止 = 次日 00:00（不含）</b>——
 *       即"止"那天是**包含**的（{@code plusDays(1)}），少一个 plusDays 就会让"选到今天"过滤掉今天；</li>
 *   <li>{@code PUT .../status} 的状态在**请求体**里（{@code @RequestBody StatusRequest}），不是 query 参数
 *       （C1 基线里这两条用例是带体的，见 {@code p6-c1-baseline.ps1} 的注释）。</li>
 * </ol>
 *
 * <p>分页参数用 {@code @ParameterObject PageQuery}：springdoc 展开成同名的 {@code pageNum}/{@code pageSize}
 * query 参数，对外契约不变（与单体同一写法、同一默认值 1/10）。
 */
@Tag(name = "后台-会员管理")
@RestController
@RequestMapping("/api/admin/member")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping("/page")
    public ApiResponse<PageResult<MemberAdminVO>> page(
            @Parameter(description = "用户名/手机号/昵称", example = "demo") @RequestParam(required = false) String keyword,
            @Parameter(description = "状态 0禁用 1正常", example = "1") @RequestParam(required = false) Integer status,
            @Parameter(description = "注册日期起 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateStart,
            @Parameter(description = "注册日期止 yyyy-MM-dd(含)") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate createDateEnd,
            @ParameterObject PageQuery page) {
        return ApiResponse.ok(adminMemberService.page(keyword, status,
                createDateStart == null ? null : createDateStart.atStartOfDay(),
                createDateEnd == null ? null : createDateEnd.plusDays(1).atStartOfDay(),
                page.getPageNum(), page.getPageSize()));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberAdminVO> detail(@Parameter(description = "会员ID", example = "1") @PathVariable Long id) {
        return ApiResponse.ok(adminMemberService.detail(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@Parameter(description = "会员ID", example = "1") @PathVariable Long id,
                                          @RequestBody StatusRequest request) {
        adminMemberService.updateStatus(id, request.getStatus());
        return ApiResponse.ok();
    }
}
