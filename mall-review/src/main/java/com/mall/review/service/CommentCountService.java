package com.mall.review.service;

import com.mall.review.support.dto.CommentBriefVO;

/**
 * 评价的只读入口（P4 第 1 批落地的最小契约）。
 *
 * <p>只做两件事：数数、按 id 取一条评价快照。它存在的意义是让本批**可验证**——
 * "库建好了、数据搬过来了、服务能读到"必须有断言守着，而不是靠"它编译通过了"。
 *
 * <p>刻意保持"薄"（与 P0 的域服务接口同一套要求）：不放业务规则、不写数据。
 * 评价的提交/展示/管理（含 409「同一订单明细不能重复评价」的抢闸门、
 * 好评率统计、管理端审核）在 P4 后续批次整体搬过来时补齐；
 * 这里先只做"跑得起来、连得上自己的库"的最小闭环，避免一次性搬一大坨代码却无法验证。
 */
public interface CommentCountService {

    /**
     * 评价总数。
     *
     * <p>口径：**已逻辑删除的不算**（实体上的 {@code @TableLogic} 负责加 {@code deleted = 0}）。
     * 这与单体 {@code CommentServiceImpl} 的统计口径一致——C1 要求对外数值不变。
     * 注意这里**不**按 {@code status} 过滤（待审核/隐藏也算"存在这条评价"）；
     * 对外展示与好评率的口径由后续批次的展示服务按 {@code CommentStatus} 处理。
     */
    long count();

    /**
     * 按 id 取一条评价快照；不存在（含已逻辑删除）返回 {@code null}。
     *
     * <p>返回 null 而不是抛异常：调用方（trade 的对账、admin 的聚合）需要区分
     * "这条评价不存在"与"查询失败"，前者是正常业务结果，后者才该重试。
     */
    CommentBriefVO findById(long id);
}
