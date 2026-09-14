package com.mall.demo.admin.service;

import com.mall.demo.admin.dto.MemberAdminVO;
import com.mall.demo.common.PageResult;

import java.time.LocalDateTime;

/**
 * 后台会员管理(见《接口文档.md》3.7)。
 */
public interface AdminMemberService {

    /**
     * 会员分页。
     *
     * @param createTimeStart 注册时间起(含)，null 不限
     * @param createTimeEnd   注册时间止(不含)，null 不限
     */
    PageResult<MemberAdminVO> page(String keyword, Integer status,
                                   LocalDateTime createTimeStart, LocalDateTime createTimeEnd,
                                   long pageNum, long pageSize);

    MemberAdminVO detail(Long id);

    void updateStatus(Long id, Integer status);
}
