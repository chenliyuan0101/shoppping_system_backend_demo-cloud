package com.mall.content.support.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 类目树节点(后台/前台通用)。
 */
@Data
public class CategoryNode {

    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private Integer status;
    private List<CategoryNode> children = new ArrayList<>();
}
