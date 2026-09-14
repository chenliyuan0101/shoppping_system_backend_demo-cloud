package com.mall.demo.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 死信重投请求。
 */
@Data
public class MqDlqRequeueRequest {

    @Schema(description = "死信队列名；留空=重投全部三个死信队列",
            example = "mall.pms.es-sync.dlq")
    private String queue;

    @Schema(description = "本次最多重投多少条(1~1000，默认 100)", example = "100")
    private Integer limit;
}
