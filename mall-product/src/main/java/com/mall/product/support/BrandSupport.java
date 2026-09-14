package com.mall.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.domain.Brand;
import com.mall.product.mapper.BrandMapper;

import java.util.List;

/**
 * 品牌查询共用封装(前台品牌下拉与后台"启用品牌列表"是同一个查询)。
 */
public final class BrandSupport {

    private BrandSupport() {
    }

    /** 启用的品牌(按 sort、id 升序) */
    public static List<Brand> enabledBrands(BrandMapper brandMapper) {
        return brandMapper.selectList(new LambdaQueryWrapper<Brand>()
                .eq(Brand::getStatus, EnableStatus.ENABLED)
                .orderByAsc(Brand::getSort).orderByAsc(Brand::getId));
    }
}
