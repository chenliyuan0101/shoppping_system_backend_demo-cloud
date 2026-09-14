package com.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.admin.domain.AdminUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@code sys_user} 的 mapper。
 *
 * <p>⚠️ 只扫本服务的包（{@code com.mall.admin.mapper}，见 {@code MybatisPlusConfig}）——
 * 每个服务自带组装根，绝不去扫别人的 mapper 包（那会把"库边界"悄悄抹掉）。
 */
@Mapper
public interface AdminUserMapper extends BaseMapper<AdminUser> {
}
