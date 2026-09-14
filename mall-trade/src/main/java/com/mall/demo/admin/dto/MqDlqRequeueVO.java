package com.mall.demo.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 死信重投结果(字段名与原 Map 键一一对应)。
 *
 * <p>接口按入参返回两种形状，而 HTTP 响应体只能声明一个 data 类型，因此共用本 VO：
 * <ul>
 *   <li>指定 queue(重投单个队列)：queue / requeued / failed / remaining</li>
 *   <li>不指定 queue(重投全部)：totalRequeued / results(元素只含 queue/requeued/failed/remaining)</li>
 * </ul>
 * 用 NON_NULL 保证两种形状的 JSON 与改造前逐字一致(未使用的字段不出现在 JSON 里)。
 */
@Data
@Schema(description = "死信重投结果")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MqDlqRequeueVO {

    /** 死信队列名(重投单个队列) */
    private String queue;

    /** 重投成功条数(重投单个队列) */
    private Integer requeued;

    /** 重投失败条数，消息已放回死信(重投单个队列) */
    private Integer failed;

    /** 重投后队列剩余条数，-1 = 读取失败(重投单个队列) */
    private Long remaining;

    /** 重投全部时的成功总条数 */
    private Integer totalRequeued;

    /** 重投全部时各死信队列的结果 */
    private List<MqDlqRequeueVO> results;
}
