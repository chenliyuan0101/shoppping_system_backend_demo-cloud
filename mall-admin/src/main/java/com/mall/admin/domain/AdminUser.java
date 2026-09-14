package com.mall.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台管理员，对应 {@code mall_admin.sys_user}（见《数据库设计文档.md》4.17）。
 *
 * <p>逐字平移单体 {@code com.mall.demo.admin.domain.AdminUser}：字段名与列名一一对应
 * （MyBatis-Plus 的驼峰映射），**没有逻辑删除列** —— {@code sys_user} 只有那 7 列，
 * 表结构见 {@code db/01-mall_admin-schema.sql}（由真实 {@code mall.sys_user} 的
 * {@code SHOW CREATE TABLE} 导出，不是手写的）。
 *
 * <p>⚠️ 本服务是这张表的**唯一属主**（P7 §1）：其它服务/网关**都不许读它** ——
 * 网关需要的那点状态走 {@code mall:cache:admin:status:{id}}（本服务写入，见
 * {@link com.mall.admin.support.AdminStatusCache}）。
 */
@Data
@TableName("sys_user")
public class AdminUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文 */
    private String password;

    private String nickname;

    /** 0禁用 1正常（{@link com.mall.admin.support.constant.EnableStatus}） */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
