package com.mall.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 后台登录成功响应（逐字平移单体 {@code com.mall.demo.admin.dto.AdminLoginResponse}）。
 *
 * <p>JSON 形状是**对外契约**（C1）：{@code {"code":0,"message":"ok","data":{"token":"…","admin":{"id":1,"username":"admin","nickname":"超级管理员","status":1}}}}
 * <ul>
 *   <li>{@code data.token} —— HS256 / {@code typ=admin} / 带 {@code ver} 的管理端令牌；</li>
 *   <li>{@code data.admin} —— {@code admin.id/username/nickname/status} 四个字段（顺序无关，键集合必须相同）。</li>
 * </ul>
 * ⚠️ 这个形状被 C1 基线里的 `admin-*` 用例间接依赖（33 项后台用例全部先调 login 拿令牌），
 * 也直接决定前端管理后台（`vue-admin`，5174，base `/admin/`）的解析：`Login.vue` 读
 * `data.token` 与 `data.admin`。
 */
@Data
@AllArgsConstructor
public class AdminLoginResponse {

    private String token;

    private AdminInfo admin;

    @Data
    @Builder
    public static class AdminInfo {
        private Long id;
        private String username;
        private String nickname;
        private Integer status;
    }
}
