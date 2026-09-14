package com.mall.usercenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.usercenter.domain.CartItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}
