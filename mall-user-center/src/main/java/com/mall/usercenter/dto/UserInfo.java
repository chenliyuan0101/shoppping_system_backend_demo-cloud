package com.mall.usercenter.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外返回的会员信息(不含密码)。
 */
@Data
@Builder
public class UserInfo {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private String phone;
    /** 0禁用 1正常 */
    private Integer status;
    private LocalDateTime createTime;
}
