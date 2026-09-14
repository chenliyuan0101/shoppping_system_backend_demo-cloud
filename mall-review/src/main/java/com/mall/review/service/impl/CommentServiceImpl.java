package com.mall.review.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mall.review.client.MemberSnapshotClient;
import com.mall.review.domain.Comment;
import com.mall.review.domain.ReviewPendingItem;
import com.mall.review.dto.CommentsResult;
import com.mall.review.dto.MineCommentVO;
import com.mall.review.dto.PublicCommentVO;
import com.mall.review.dto.SubmitCommentRequest;
import com.mall.review.mapper.CommentMapper;
import com.mall.review.mapper.ReviewPendingItemMapper;
import com.mall.review.service.CommentService;
import com.mall.review.support.BusinessException;
import com.mall.review.support.JsonKit;
import com.mall.review.support.PageKit;
import com.mall.review.support.PageResult;
import com.mall.review.support.RequestValidator;
import com.mall.review.support.constant.CommentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 评价实现：<b>校验与抢占全部在本地</b>（本服务的 {@code review_pending_item} + {@code pms_comment}）。
 *
 * <p>本类从单体 {@code com.mall.demo.pms.service.impl.CommentServiceImpl} 逐条搬来，
 * <b>校验顺序、错误码与文案一字未改</b>（C1 基线的 8 条文案逐字保留），
 * 但数据来源整条换掉：
 *
 * <table border="1">
 *   <caption>P4 前后对照</caption>
 *   <tr><th>环节</th><th>改造前（单体 pms）</th><th>现在（mall-review）</th></tr>
 *   <tr><td>订单是否存在/是否我的</td><td>同步调 trade 的 {@code findCommentableOrder}</td>
 *       <td>本地 {@code review_pending_item} 按 order_no 查（读模型只由 {@code order.finished} 投影）</td></tr>
 *   <tr><td>订单是否已完成</td><td>{@code oms_order.order_status == FINISHED}</td>
 *       <td>本地的收货时间：投影行存在即已确认收货；收货时间还没到 → 尚未完成</td></tr>
 *   <tr><td>是否超过可评价期限</td><td>{@code oms_order.finish_time}</td>
 *       <td>同一列的本地副本（同一配置键 {@code mall.comment.limit-days}，随代码搬来）</td></tr>
 *   <tr><td>防重复评价（并发凭证）</td><td>跨库 CAS {@code UPDATE oms_order_item SET comment_status=1 WHERE id=? AND comment_status=0}</td>
 *       <td><b>本地闸门</b> {@code UPDATE review_pending_item SET commented=1, comment_id=? WHERE order_item_id=? AND commented=0}，
 *           影响行数即抢到与否（与 P3 购物车闸门同构）</td></tr>
 *   <tr><td>昵称</td><td>展示时跨域查会员域</td>
 *       <td><b>写入时落快照</b> {@code pms_comment.member_nickname}，展示只读本表</td></tr>
 * </table>
 *
 * <p><b>事务语义反而变干净了</b>：改造前"跨库 CAS + 插入评价"虽在同一个本地事务里，
 * 但那是因为两张表恰好在同一个库；现在闸门与评价行都在 {@code mall_review}，
 * 一条 UPDATE + 一条 INSERT 同事务，"某一明细失败则整批回滚"的语义仍然成立。
 *
 * <p><b>一处刻意的顺序差异</b>：闸门 UPDATE 需要 {@code comment_id}（"这条明细最终评的是哪条评论"），
 * 而评价 id 要等 INSERT 之后才由数据库给出。因此本实现的顺序是
 * <b>先 INSERT 评价行 → 再条件 UPDATE 抢闸门</b>，抢不到（影响 0 行）就抛 409
 * ——整批回滚会把刚插入的评价行一起撤销，对外表现与"先抢后写"完全一致，
 * 区别只在 {@code comment_id} 有真实值可写（对账需要）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** 商品评价列表每页上限（与改造前 {@code ProductPortalServiceImpl.MAX_PAGE_SIZE} 一致） */
    private static final long MAX_PAGE_SIZE = 50;

    private final CommentMapper commentMapper;
    private final ReviewPendingItemMapper reviewPendingItemMapper;
    /** 昵称快照的唯一来源（取不到就落空串，绝不影响提交） */
    private final MemberSnapshotClient memberSnapshotClient;
    /** 请求参数校验(items 的 @NotEmpty 写在 DTO 上，此处统一触发) */
    private final RequestValidator requestValidator;

    @Value("${mall.comment.limit-days:90}")
    private long limitDays;

    // ==================================================================
    // 一、提交评价（全本地）
    // ==================================================================

    @Override
    @Transactional
    public void submit(Long memberId, SubmitCommentRequest request) {
        // ① 400「请填写评价内容」：items 为空/缺失（校验写在 DTO 上，这里统一触发）
        requestValidator.check(request);

        // ② 本地读模型：这个订单在本服务眼里的全部待评价明细（不是我的 → 404「订单不存在」）
        List<ReviewPendingItem> orderItems = loadOrderItems(request.getOrderNo(), memberId);

        // ③ 409「订单完成后才能评价」
        LocalDateTime finishTime = orderFinishTime(orderItems);
        if (finishTime == null || finishTime.isAfter(LocalDateTime.now())) {
            throw new BusinessException(409, "订单完成后才能评价");
        }
        // ④ 409「已超过 N 天可评价期限」（N 来自配置 mall.comment.limit-days，随代码一起搬来）
        if (finishTime.isBefore(LocalDateTime.now().minusDays(limitDays))) {
            throw new BusinessException(409, "已超过 " + limitDays + " 天可评价期限");
        }

        Map<Long, ReviewPendingItem> itemMap = orderItems.stream()
                .collect(Collectors.toMap(ReviewPendingItem::getOrderItemId, Function.identity()));

        // 昵称快照在"这次提交一定会写评价"之后取一次（不放进循环：同一会员的昵称只需要一个）
        String nickname = memberSnapshotClient.nickname(memberId);

        // 同一请求里重复提交同一个 orderItemId：itemMap 里的对象不会因为"上一个循环把它置 1"而变化，
        // 所以只靠 commented 判断会放行两次（同一订单明细写出两条评价，污染评价数与好评率）
        Set<Long> seen = new HashSet<>();

        for (SubmitCommentRequest.CommentItem item : request.getItems()) {
            if (item.getOrderItemId() == null || !itemMap.containsKey(item.getOrderItemId())) {
                throw new BusinessException(400, "评价条目不属于该订单");
            }
            if (!seen.add(item.getOrderItemId())) {
                throw new BusinessException(400, "同一订单明细不能重复评价");
            }
            ReviewPendingItem orderItem = itemMap.get(item.getOrderItemId());
            if (orderItem.getCommented() != null && orderItem.getCommented() == 1) {
                // 【分支 A】快照预检：读模型里这条明细已经是已评价
                // —— 与下面【分支 B】文案相同但触发条件不同，刻意保留为两个分支，不合并
                throw new BusinessException(409, "该商品已评价");
            }
            if (item.getRating() == null || item.getRating() < 1 || item.getRating() > 5) {
                throw new BusinessException(400, "评分须为 1~5");
            }

            Comment comment = new Comment();
            comment.setMemberId(memberId);
            comment.setMemberNickname(nickname);
            comment.setOrderNo(request.getOrderNo());
            comment.setOrderItemId(orderItem.getOrderItemId());
            comment.setSpuId(orderItem.getSpuId());
            comment.setSkuId(orderItem.getSkuId());
            comment.setRating(item.getRating());
            comment.setContent(item.getContent() == null ? "" : item.getContent());
            comment.setImages(item.getImages() == null || item.getImages().isEmpty()
                    ? null : JsonKit.toJson(item.getImages()));
            comment.setStatus(CommentStatus.VISIBLE);   // 一期直接展示
            commentMapper.insert(comment);

            // 本地闸门：影响行数 = 并发凭证。抢不到说明另一请求（或历史回填）已经占了这条明细，
            // 抛 409 让整个事务回滚，上面那条评价行随之撤销 —— 不会留下第二条评价。
            int claimed = reviewPendingItemMapper.claim(orderItem.getOrderItemId(), comment.getId());
            if (claimed == 0) {
                // 【分支 B】抢占失败（与上面【分支 A】同文案、不同触发条件，不得合并）
                throw new BusinessException(409, "该商品已评价");
            }
        }
    }

    /**
     * 读这个订单在本服务眼里的待评价明细。
     *
     * <p>两条判定都落在本地读模型上（拆服务后"订单"这个概念在本服务里就是这些投影行）：
     * <ul>
     *   <li>没有任何一行 → {@code 404「订单不存在」}（订单不存在，或事件还没投影过来）；</li>
     *   <li>有行但一行都不属于当前会员 → 同样 {@code 404「订单不存在」}
     *       —— 与改造前一致：<b>不给出"这单存在但不是你的"这种可探测信息</b>。</li>
     * </ul>
     * 只返回属于当前会员的行：别人的明细因此不会出现在 {@code itemMap} 里，
     * 落到循环里就是 400「评价条目不属于该订单」（与改造前的表现一致）。
     */
    private List<ReviewPendingItem> loadOrderItems(String orderNo, Long memberId) {
        if (!StringUtils.hasText(orderNo)) {
            // 请求体没带订单号：改造前是 SQL 等值 null 匹配不到任何行 → 同一个 404
            throw new BusinessException(404, "订单不存在");
        }
        List<ReviewPendingItem> rows = reviewPendingItemMapper.selectList(
                new LambdaQueryWrapper<ReviewPendingItem>().eq(ReviewPendingItem::getOrderNo, orderNo));
        List<ReviewPendingItem> mine = rows.stream()
                .filter(r -> Objects.equals(r.getMemberId(), memberId))
                .toList();
        if (mine.isEmpty()) {
            throw new BusinessException(404, "订单不存在");
        }
        return mine;
    }

    /**
     * 订单的"确认收货时刻"：该订单所有明细里<b>最早</b>的收货时间。
     *
     * <p>同一订单的明细来自同一条 {@code order.finished} 事件，收货时间本来一致；
     * 取最小值是保守口径（任何一条明细超期都算这单超期），与改造前"读 {@code oms_order.finish_time}
     * 一次、在循环之前判定"的顺序也一致（超期是订单粒度的判定，不是明细粒度）。
     */
    private static LocalDateTime orderFinishTime(List<ReviewPendingItem> items) {
        return items.stream()
                .map(ReviewPendingItem::getFinishedTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    // ==================================================================
    // 二、商品评价（公开读，全部本地）
    // ==================================================================

    @Override
    @Transactional(readOnly = true)
    public CommentsResult productComments(Long spuId, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));

        long goodCount = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getSpuId, spuId).eq(Comment::getStatus, CommentStatus.VISIBLE).ge(Comment::getRating, 4));
        long total = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getSpuId, spuId).eq(Comment::getStatus, CommentStatus.VISIBLE));
        List<Comment> list = commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getSpuId, spuId).eq(Comment::getStatus, CommentStatus.VISIBLE)
                .orderByDesc(Comment::getCreateTime).orderByDesc(Comment::getId)
                .last("LIMIT " + ((page - 1) * size) + "," + size));

        List<PublicCommentVO> vos = list.stream().map(c -> {
            PublicCommentVO v = new PublicCommentVO();
            v.setId(c.getId());
            v.setRating(c.getRating());
            v.setContent(c.getContent());
            v.setImages(JsonKit.toImageList(c.getImages()));
            // 昵称走本表的历史快照，**不再跨服务查会员域**（R4：这是 P4 拆掉的那条读边）；
            // 兜底文案与改造前"查不到昵称"时完全一致
            v.setNickname(StringUtils.hasText(c.getMemberNickname()) ? c.getMemberNickname() : "匿名用户");
            v.setCreateTime(c.getCreateTime());
            return v;
        }).toList();

        int goodRate = total == 0 ? 100 : (int) Math.round(goodCount * 100.0 / total);
        return new CommentsResult(goodRate, PageResult.of(total, page, size, vos));
    }

    // ==================================================================
    // 三、我的评价（需登录，全部本地）
    // ==================================================================

    @Override
    @Transactional(readOnly = true)
    public PageResult<MineCommentVO> mine(Long memberId, long pageNum, long pageSize) {
        long page = PageKit.page(pageNum);   // 页码上限收敛，避免 (page-1)*size 溢出成负 offset
        long size = PageKit.size(pageSize, MAX_PAGE_SIZE);
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .eq(Comment::getMemberId, memberId)
                .orderByDesc(Comment::getCreateTime);
        long total = commentMapper.selectCount(wrapper);
        List<Comment> list = commentMapper.selectList(wrapper.last("LIMIT " + ((page - 1) * size) + "," + size));

        // 商品标题：**不回头查商品域**，直接读待评价读模型里的下单快照（{@code review_pending_item.spu_title}）。
        // 读模型里没有这条明细时该字段为 null——与改造前"SPU 已被删除"的表现一致。
        Map<Long, String> spuTitles = list.isEmpty() ? Map.of()
                : reviewPendingItemMapper.selectBatchIds(list.stream().map(Comment::getOrderItemId)
                        .filter(Objects::nonNull).distinct().toList())
                .stream()
                .filter(i -> i.getSpuTitle() != null)
                .collect(Collectors.toMap(ReviewPendingItem::getOrderItemId, ReviewPendingItem::getSpuTitle,
                        (a, b) -> a));

        List<MineCommentVO> vos = list.stream().map(c -> {
            MineCommentVO vo = new MineCommentVO();
            vo.setId(c.getId());
            vo.setSpuId(c.getSpuId());
            vo.setSpuTitle(c.getOrderItemId() == null ? null : spuTitles.get(c.getOrderItemId()));
            vo.setRating(c.getRating());
            vo.setContent(c.getContent());
            vo.setImages(JsonKit.toImageList(c.getImages()));
            vo.setCreateTime(c.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(total, page, size, vos);
    }
}
