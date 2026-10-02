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

    /**
     * 浏览量批量回写（ScheduledTasks.flushViewCounts 每 10 分钟一次）。
     * updated_at 会随之刷新，ES 增量同步因此能顺带把最新 viewCount 带过去。
     */
    @Update("UPDATE item SET view_count = view_count + #{delta} WHERE id = #{itemId} AND #{delta} > 0")
    int incrViewBy(@Param("itemId") Long itemId, @Param("delta") int delta);

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

    /**
     * 卖家二次申请上架（仅限「违规下架(8)」）。
     *
     * <p>与 {@link #releaseLock} 不同：这里把商品置为「审核中(1)」而<b>不是直接(2)在售</b>，
     * 因为在售期间它还不能对外可见——要等审核员在「上架审核」页点通过。
     * 条件更新兜并发：连点两次只会生效一次。
     */
    @Update("UPDATE item SET status = 1, version = version + 1 "
            + "WHERE id = #{itemId} AND status = 8")
    int applyRelist(@Param("itemId") Long itemId);

    /** 记录「审核员已看过的那一版内容」快照，用于识别在售期间的改名/改描述 */
    @Update("UPDATE item SET last_live_title = #{title}, last_live_desc_hash = #{descHash} WHERE id = #{itemId}")
    int markLiveSnapshot(@Param("itemId") Long itemId, @Param("title") String title,
                         @Param("descHash") String descHash);

    /**
     * 上架审核页的「下架」：把商品撤下来，但<b>不</b>记成违规。
     *
     * <p>与 {@link #forceOffline} 的区别是处置力度：
     * <ul>
     *   <li>这里 → 已下架(5)：内容看着不对但够不上违规，卖家可以改完重新上架；</li>
     *   <li>forceOffline → 违规下架(8)：明确违规，卖家不能自己解除，要二次申请。</li>
     * </ul>
     * 已售出(4) 与已下架(5) 没有可撤的东西，条件更新自然返回 0 行。
     *
     * <p>同时清掉机审字段与锁库信息：这个商品已经不在售了，{@code audit_result=1}（当时机审通过）
     * 留着会让"商品管理"页看起来像是刚审核通过的商品；锁库信息不清则会留下一条永远等不到释放的锁。
     */
    @Update("UPDATE item SET status = 5, lock_user_id = NULL, lock_expire_at = NULL, "
            + "audit_result = NULL, audit_reason = NULL, version = version + 1 "
            + "WHERE id = #{itemId} AND status IN (1, 2, 3, 6)")
    int downlist(@Param("itemId") Long itemId);
}
