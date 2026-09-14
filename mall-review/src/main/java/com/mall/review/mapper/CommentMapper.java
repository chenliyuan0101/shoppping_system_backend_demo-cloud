package com.mall.review.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.review.domain.Comment;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论表的 Mapper（只扫本服务的 {@code com.mall.review.mapper} 包，见 MybatisPlusConfig）。
 *
 * <p>P4 第 1 批只用到 {@code BaseMapper} 自带的 {@code selectCount}/{@code selectById}：
 * 需要证明的是"这个服务真的能读它自己迁过来的数据"，还不到写自定义 SQL 的时候。
 * 评价列表的按 SPU 分页、好评率聚合会在业务搬过来时加在这里。
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
}
