package com.ershou.campus.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.chat.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 未读数用条件自增而不是"读-改-写"：两个人同时发消息也不会丢计数。
 * user_a 是较小的 id，所以"对方是 user_b 时"要给 unread_a 加一。
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {

    @Update("UPDATE chat_session SET unread_a = unread_a + 1 WHERE id = #{id}")
    int incrUnreadA(@Param("id") Long id);

    @Update("UPDATE chat_session SET unread_b = unread_b + 1 WHERE id = #{id}")
    int incrUnreadB(@Param("id") Long id);

    @Update("UPDATE chat_session SET unread_a = 0 WHERE id = #{id}")
    int clearUnreadA(@Param("id") Long id);

    @Update("UPDATE chat_session SET unread_b = 0 WHERE id = #{id}")
    int clearUnreadB(@Param("id") Long id);
}
