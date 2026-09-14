package com.mall.usercenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 浏览足迹，对应 ums_footprint(member_id+spu_id 唯一，浏览刷新时间)。
 */
@Data
@TableName("ums_footprint")
public class Footprint {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private Long spuId;

    private LocalDateTime lastViewTime;
}
