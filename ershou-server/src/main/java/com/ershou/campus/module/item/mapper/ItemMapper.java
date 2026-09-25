package com.ershou.campus.module.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.item.entity.Item;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ItemMapper extends BaseMapper<Item> {

    /**
     * 条件更新锁库存（DB 层兜底，防超卖）。影响行数 0 表示已被他人锁定或已售出。
     */
    @Update("UPDATE item SET status = 3, lock_user_id = #{userId}, lock_expire_at = #{expireAt}, version = version + 1 "
            + "WHERE id = #{itemId} AND status = 2")
    int lockStock(@Param("itemId") Long itemId, @Param("userId") Long userId, @Param("expireAt") LocalDateTime expireAt);

    /** 释放锁库，回到在售 */
    @Update("UPDATE item SET status = 2, lock_user_id = NULL, lock_expire_at = NULL, version = version + 1 "
            + "WHERE id = #{itemId} AND status = 3")
    int releaseLock(@Param("itemId") Long itemId);

    /** 成交：置为已售出并下架 */
    @Update("UPDATE item SET status = 4, stock = 0, lock_user_id = NULL, lock_expire_at = NULL, version = version + 1 "
            + "WHERE id = #{itemId} AND status IN (2, 3)")
    int markSold(@Param("itemId") Long itemId);

    @Update("UPDATE item SET view_count = view_count + 1 WHERE id = #{itemId}")
    int incrView(@Param("itemId") Long itemId);

    @Update("UPDATE item SET fav_count = GREATEST(fav_count + #{delta}, 0) WHERE id = #{itemId}")
    int addFavCount(@Param("itemId") Long itemId, @Param("delta") int delta);

    /** 运营审核通过：仅"审核中"可通过（条件更新兜底并发重复审核） */
    @Update("UPDATE item SET status = 2, audit_result = 1, audit_reason = #{reason}, reject_code = NULL, version = version + 1 "
            + "WHERE id = #{itemId} AND status = 1")
    int approveAudit(@Param("itemId") Long itemId, @Param("reason") String reason);

    /** 运营审核驳回：仅"审核中"可驳回 */
    @Update("UPDATE item SET status = 6, audit_result = 2, audit_reason = #{reason}, reject_code = #{rejectCode}, version = version + 1 "
            + "WHERE id = #{itemId} AND status = 1")
    int rejectAudit(@Param("itemId") Long itemId, @Param("reason") String reason, @Param("rejectCode") String rejectCode);

    /** 运营强制下架（违规）：审核中/在售/已锁定/已驳回 都可强制下架 */
    @Update("UPDATE item SET status = 8, audit_result = 2, audit_reason = #{reason}, version = version + 1 "
            + "WHERE id = #{itemId} AND status IN (1, 2, 3, 6)")
    int forceOffline(@Param("itemId") Long itemId, @Param("reason") String reason);
}
