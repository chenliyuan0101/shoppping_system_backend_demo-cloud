package com.mall.usercenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 前台会员，对应表 ums_member(见《数据库设计文档.md》4.1)。
 * create_time/update_time 由数据库默认值维护；deleted 由 @TableLogic 处理。
 */
@Data
@TableName("ums_member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** BCrypt 密文 */
    private String password;

    private String nickname;

    private String phone;

    private String avatar;

    /** 0未知 1男 2女 */
    private Integer gender;

    /** 0禁用 1正常 */
    private Integer status;

    private LocalDateTime lastLoginTime;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
