package com.mall.usercenter.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 商品收藏，对应 ums_favorite。
 */
@Data
@TableName("ums_favorite")
public class Favorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private Long spuId;

    private LocalDateTime createTime;
}
