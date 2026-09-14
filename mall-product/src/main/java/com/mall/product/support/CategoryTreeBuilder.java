package com.mall.product.support;

import com.mall.product.support.dto.CategoryNode;
import com.mall.product.domain.Category;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 类目树构建(前台首页/后台类目管理共用)：把平铺类目列表组装成两级树。
 * 调用方自行决定过滤规则(前台传启用类目，后台传全部)。
 */
public final class CategoryTreeBuilder {

    private CategoryTreeBuilder() {
    }

    /** 组树：parentId=0/null 为顶级，其余挂到父节点 children 下 */
    public static List<CategoryNode> build(List<Category> all) {
        Map<Long, List<Category>> byParent = all.stream()
                .collect(Collectors.groupingBy(c -> c.getParentId() == null ? 0L : c.getParentId()));
        List<CategoryNode> roots = new ArrayList<>();
        for (Category c : all) {
            if (c.getParentId() == null || c.getParentId() == 0) {
                CategoryNode node = toNode(c);
                node.setChildren(toNodes(byParent.getOrDefault(c.getId(), List.of()), byParent));
                roots.add(node);
            }
        }
        return roots;
    }

    private static List<CategoryNode> toNodes(List<Category> list, Map<Long, List<Category>> byParent) {
        List<CategoryNode> nodes = new ArrayList<>();
        for (Category c : list) {
            CategoryNode node = toNode(c);
            node.setChildren(toNodes(byParent.getOrDefault(c.getId(), List.of()), byParent));
            nodes.add(node);
        }
        return nodes;
    }

    public static CategoryNode toNode(Category c) {
        CategoryNode node = new CategoryNode();
        node.setId(c.getId());
        node.setParentId(c.getParentId());
        node.setName(c.getName());
        node.setSort(c.getSort());
        node.setStatus(c.getStatus());
        return node;
    }
}
