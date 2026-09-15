package com.mall.review.service.impl;

import com.mall.review.domain.Comment;
import com.mall.review.mapper.CommentMapper;
import com.mall.review.service.CommentCountService;
import com.mall.common.support.JsonKit;
import com.mall.review.support.dto.CommentBriefVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评价只读实现：只读自己的表、只产出契约快照。
 *
 * <p>三个刻意的取舍：
 * <ol>
 *   <li>{@code count()} 用 {@code selectCount(null)}：逻辑删除由实体上的 {@code @TableLogic} 处理，
 *       不需要手写 {@code deleted = 0}——手写会出现"实体注解与 SQL 两处口径不一致"的经典 bug；</li>
 *   <li>返回 {@link CommentBriefVO} 而不是实体：实体是持久层类型，不作域间契约
 *       （P0 的 B2 规则拦的就是这件事），而且下批加列时不会静默改变对外 JSON；</li>
 *   <li>{@code images} 的 JSON 解析放在这里而不是 Controller：解析失败时应该是
 *       "这条评价的图片为空 + 日志"，不该让整个列表接口 500。
 *       目前 {@code JsonKit.toImageList} 对非法 JSON 抛 IllegalStateException，
 *       由全局异常处理器兜成 500——**存量数据实测**（2502 行）images 全部是
 *       NULL 或合法数组，所以先按"数据可信"处理；真要兜底会在展示批次里连同
 *       "脏数据长什么样"一起做，而不是在这里默默吞掉。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CommentCountServiceImpl implements CommentCountService {

    private final CommentMapper commentMapper;

    @Override
    @Transactional(readOnly = true)
    public long count() {
        Long total = commentMapper.selectCount(null);
        return total == null ? 0L : total;
    }

    @Override
    @Transactional(readOnly = true)
    public CommentBriefVO findById(long id) {
        Comment comment = commentMapper.selectById(id);
        return comment == null ? null : toVO(comment);
    }

    private static CommentBriefVO toVO(Comment c) {
        return new CommentBriefVO(
                c.getId(),
                c.getMemberId(),
                c.getMemberNickname(),
                c.getOrderNo(),
                c.getOrderItemId(),
                c.getSpuId(),
                c.getSkuId(),
                c.getRating(),
                c.getContent(),
                JsonKit.toImageList(c.getImages()),
                c.getStatus(),
                c.getCreateTime());
    }
}
