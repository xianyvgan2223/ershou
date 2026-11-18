package com.ershou.cloud.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.cloud.chat.entity.ChatSession;
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

    /** 删除聊天记录（每用户软删除）：只标记我这一侧，未读一并归零。 */
    @Update("UPDATE chat_session SET "
            + "deleted_a = IF(#{sinceA}, 1, deleted_a), deleted_b = IF(#{sinceA}, deleted_b, 1), "
            + "deleted_a_at = IF(#{sinceA}, NOW(), deleted_a_at), "
            + "deleted_b_at = IF(#{sinceA}, deleted_b_at, NOW()), "
            + "unread_a = IF(#{sinceA}, 0, unread_a), unread_b = IF(#{sinceA}, unread_b, 0) "
            + "WHERE id = #{id}")
    int markDeleted(@Param("id") Long id, @Param("sinceA") boolean sinceA);

    /** 撤销删除（再次从商品发起会话 / 对方又发来消息）。 */
    @Update("UPDATE chat_session SET deleted_a = 0, deleted_b = 0, "
            + "deleted_a_at = NULL, deleted_b_at = NULL WHERE id = #{id} "
            + "AND (deleted_a = 1 OR deleted_b = 1)")
    int restoreDeleted(@Param("id") Long id);
}
