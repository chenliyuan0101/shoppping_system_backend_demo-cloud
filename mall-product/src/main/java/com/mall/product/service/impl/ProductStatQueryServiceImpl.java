package com.mall.product.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.common.support.PageKit;
import com.mall.product.support.constant.EnableStatus;
import com.mall.product.support.dto.SpuSnapshotVO;
import com.mall.product.domain.Spu;
import com.mall.product.mapper.SpuMapper;
import com.mall.product.service.ProductStatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品看板统计契约实现：热度榜与在架数都直接在 DB 排序/计数，不做全表捞取后内存排序。
 *
 * <p><b>P4 批次 3：{@code commentCountByMember} 已移除</b>（它读 {@code pms_comment}，
 * 而那张表连同评价域一起搬去了 {@code mall-review}）。本实现现在只依赖 {@code pms_spu}——
 * "商品域不再有任何读评价表的代码"由此变成编译期事实，而不是靠自觉。
 */
@Service
@RequiredArgsConstructor
public class ProductStatQueryServiceImpl implements ProductStatQueryService {

    /** 榜单条数上限（与改造前 admin 侧的口径一致） */
    private static final int MAX_TOP_LIMIT = 20;

    private final SpuMapper spuMapper;

    @Override
    @Transactional(readOnly = true)
    public long countEnabled() {
        Long count = spuMapper.selectCount(new LambdaQueryWrapper<Spu>()
                .eq(Spu::getStatus, EnableStatus.ENABLED));
        return count == null ? 0 : count;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpuSnapshotVO> topBySales(int limit) {
        int n = (int) PageKit.size(limit, MAX_TOP_LIMIT);
        // 排序/取前 N 都在 DB
        return spuMapper.selectList(new LambdaQueryWrapper<Spu>()
                        .eq(Spu::getStatus, EnableStatus.ENABLED)
                        .orderByDesc(Spu::getSales)
                        .last("LIMIT 0," + n))
                .stream()
                .map(ProductQueryServiceImpl::toSpuSnapshot)
                .toList();
    }
}
