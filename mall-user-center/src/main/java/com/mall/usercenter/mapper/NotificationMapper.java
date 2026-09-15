package com.mall.usercenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.usercenter.domain.Notification;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import com.mall.common.support.MemberId;

/**
 * 站内消息 Mapper。
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {

    /**
     * 幂等写入：命中唯一键 {@code (member_id, type, biz_no)} 时什么都不做。
     *
     * <p>为什么不用"先查后插"：消息消费是 at-least-once，重复投递时并发查插会撞唯一键报错；
     * 交给数据库的唯一键更简单也更可靠（返回值 1=插入, 0=已存在）。
     *
     * <p>为什么不用 {@code INSERT IGNORE}：它会把**所有**错误都降级成警告（字段超长被静默截断、
     * 非法值被强转），排查时看不到问题。这里只忽略"唯一键冲突"这一种情况，其它错误照常抛出来。
     */
    @Insert("""
            INSERT INTO ums_notification
              (member_id, type, title, content, biz_no, is_read, create_time)
            VALUES
              (#{memberId}, #{type}, #{title}, #{content}, #{bizNo}, 0, #{createTime})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("memberId") Long memberId,
                     @Param("type") String type,
                     @Param("title") String title,
                     @Param("content") String content,
                     @Param("bizNo") String bizNo,
                     @Param("createTime") LocalDateTime createTime);
}
