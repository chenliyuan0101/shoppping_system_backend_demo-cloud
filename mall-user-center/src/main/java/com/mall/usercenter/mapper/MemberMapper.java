package com.mall.usercenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.usercenter.domain.Member;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会员 Mapper：MyBatis-Plus BaseMapper 提供 CRUD，
 * 用户名/手机号查询在 Service 层用 LambdaQueryWrapper 完成。
 */
@Mapper
public interface MemberMapper extends BaseMapper<Member> {
}
