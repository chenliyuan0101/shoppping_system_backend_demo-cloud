package com.mall.admin.support.constant;

/**
 * 「0 停用 / 1 启用」型状态列的统一定义（取值语义来自 DDL 注释与《数据库设计文档.md》）。
 *
 * <p>本服务只用到一处：{@code sys_user.status}（0 禁用 / 1 正常）。
 * ⚠️ 这个极性是**跨服务契约**：网关 {@code AdminIdentityFilter} 硬编码 {@code status != 1 ⇒ 403 账号已被禁用}，
 * 本服务写进 {@code mall:cache:admin:status:{id}} 的值必须与它同极性（P7 §2.5）。
 */
public final class EnableStatus {

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    private EnableStatus() {
    }
}
