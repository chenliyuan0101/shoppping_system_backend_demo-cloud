package com.mall.review.service.impl;

import com.mall.review.domain.ReviewPendingItem;
import com.mall.review.mapper.ReviewPendingItemMapper;
import com.mall.review.mq.OrderFinishedMessage;
import com.mall.review.service.ReviewPendingService;
import com.mall.review.support.MallTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 待评价读模型的写入实现（P4-2）。
 *
 * <p><b>幂等在这里的策略是"不判断、靠主键"</b>：不做"先 SELECT 看有没有"——
 * 那种写法在并发重复投递（两条相同的消息被两个消费者线程/两个实例同时处理）下会双写，
 * 恰好是 at-least-once 最常见的失败形态。{@link ReviewPendingItemMapper#upsert} 的
 * {@code ON DUPLICATE KEY UPDATE}（自赋值，无有意义变化）才是唯一防线：
 * 数据库把"是不是重复"这件事一次原子判定，应用层不需要窗口。
 *
 * <p>投影进来的行 {@code commented = 0}（等待评价）；历史已评价的明细由
 * {@code db/04-backfill-pending.sql} 回填成 {@code commented = 1}，
 * 因此"能不能评价"的判定永远只看本地这一列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewPendingServiceImpl implements ReviewPendingService {

    private final ReviewPendingItemMapper reviewPendingItemMapper;

    @Override
    @Transactional
    public int projectOrderFinished(OrderFinishedMessage event) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            // 没有明细就没有"待评价项"：不算失败（重投也不会变出明细来），直接返回 0 让消费者 ack
            log.warn("确认收货事件没有明细，跳过投影: orderNo={} memberId={}",
                    event == null ? null : event.orderNo(), event == null ? null : event.memberId());
            return 0;
        }

        // 时间只在这里转一次：事件是 epoch millis，列是 datetime（无时区），
        // 口径统一走业务时区 MallTime.ZONE（见该类注释：JVM 时区不是 +08:00 时否则会整体偏 8 小时）
        LocalDateTime finishedTime = MallTime.dateTimeOf(event.finishedTime());

        int inserted = 0;
        for (OrderFinishedMessage.Item item : event.items()) {
            ReviewPendingItem row = new ReviewPendingItem();
            row.setOrderItemId(item.orderItemId());
            row.setOrderNo(event.orderNo());
            row.setMemberId(event.memberId());
            row.setSpuId(item.spuId());
            row.setSkuId(item.skuId());
            // spu_title 是 NOT NULL DEFAULT ''：显式兜底成空串，避免因为一个 null 让整条事件反复重试
            row.setSpuTitle(item.spuTitle() == null ? "" : item.spuTitle());
            row.setSkuImage(item.skuImage());
            row.setQuantity(item.quantity());
            row.setFinishedTime(finishedTime);
            row.setCommented(0);
            row.setCommentId(null);
            inserted += reviewPendingItemMapper.upsert(row);
        }

        log.info("待评价读模型投影完成: orderNo={} memberId={} 明细={} 新增={} 已存在={}",
                event.orderNo(), event.memberId(), event.items().size(), inserted,
                event.items().size() - inserted);
        return inserted;
    }
}
