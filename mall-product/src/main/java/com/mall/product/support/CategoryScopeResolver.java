package com.mall.product.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.domain.Category;
import com.mall.product.mapper.CategoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 类目筛选范围解析(前台货架与后台商品列表共用)：
 * 选一级类目 → 含其全部子类；选子类 → 仅自身；为空 → null(不限)。
 */
@Component
@RequiredArgsConstructor
public class CategoryScopeResolver {

    private final CategoryMapper categoryMapper;

    /**
     * @param categoryId  选中的类目
     * @param onlyEnabled 子类是否只取启用(前台 true / 后台 false)
     * @return 用于 IN 查询的类目 id 列表；categoryId 为空返回 null 表示不加限制
     */
    public List<Long> resolve(Long categoryId, boolean onlyEnabled) {
        if (categoryId == null) {
            return null;
        }
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        Category category = categoryMapper.selectById(categoryId);
        if (category != null && (category.getParentId() == null || category.getParentId() == 0)) {
            LambdaQueryWrapper<Category> children = new LambdaQueryWrapper<Category>()
                    .eq(Category::getParentId, categoryId);
            if (onlyEnabled) {
                children.eq(Category::getStatus, EnableStatus.ENABLED);
            }
            ids.addAll(categoryMapper.selectList(children).stream().map(Category::getId).toList());
        }
        return ids;
    }
}
