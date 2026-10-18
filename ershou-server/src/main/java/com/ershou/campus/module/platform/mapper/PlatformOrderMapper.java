package com.ershou.campus.module.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ershou.campus.module.platform.vo.AdminPlatformOrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 运营台 · 平台订单只读 SQL（第 3 轮设计 3.5）。
 *
 * <p>平台订单 = {@code meetup_order.seller_id = 平台官方账号(1000)} 的面交单；
 * 这里只读不写（订单状态机与库存回收属 order-member 模块，本轮明确不做强制取消/退款）。
 *
 * <p>为什么用平台模块自有 SQL 而不是 {@code MeetupOrderMapper} + 内存 join：
 * 列表需要联 {@code sys_user}（买家昵称）与 {@code platform_consign_point}（自提点名），
 * 一条 SQL 就能分页取全，不产生 N+1，也不会把平台点名错取成 trade_point 的同 id 点位。
 */
@Mapper
public interface PlatformOrderMapper {

    /** 列表列（买家昵称 / 自提点名均来自 join） */
    String PAGE_COLUMNS = "SELECT o.id, o.order_no, o.item_id, o.item_title, o.item_cover, "
            + "o.buyer_id, u.nickname AS buyer_nickname, o.quantity, o.deal_price, o.status, "
            + "o.point_id, p.name AS point_name, o.created_at, o.verified_at "
            + "FROM meetup_order o "
            + "LEFT JOIN sys_user u ON u.id = o.buyer_id "
            + "LEFT JOIN platform_consign_point p ON p.id = o.point_id ";

    /** 平台订单分页（keyword 匹配 order_no / item_title；服务层已把空串归一成 null） */
    @Select("<script>" + PAGE_COLUMNS
            + "WHERE o.seller_id = #{sellerId} "
            + "<if test='status != null'> AND o.status = #{status}</if>"
            + "<if test='keyword != null'> AND (o.order_no LIKE CONCAT('%', #{keyword}, '%') "
            + "OR o.item_title LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + " ORDER BY o.id DESC LIMIT #{offset}, #{limit}"
            + "</script>")
    List<AdminPlatformOrderVO> selectPlatformOrders(@Param("sellerId") Long sellerId,
                                                    @Param("status") Integer status,
                                                    @Param("keyword") String keyword,
                                                    @Param("offset") int offset,
                                                    @Param("limit") int limit);

    /** 与 selectPlatformOrders 同条件的总数 */
    @Select("<script>SELECT COUNT(*) FROM meetup_order o WHERE o.seller_id = #{sellerId} "
            + "<if test='status != null'> AND o.status = #{status}</if>"
            + "<if test='keyword != null'> AND (o.order_no LIKE CONCAT('%', #{keyword}, '%') "
            + "OR o.item_title LIKE CONCAT('%', #{keyword}, '%'))</if>"
            + "</script>")
    long countPlatformOrders(@Param("sellerId") Long sellerId,
                             @Param("status") Integer status,
                             @Param("keyword") String keyword);

    // ------------------------------------------------------------ 概览统计

    @Select("SELECT COUNT(*) FROM meetup_order WHERE seller_id = #{sellerId}")
    long countAll(@Param("sellerId") Long sellerId);

    @Select("SELECT COUNT(*) FROM meetup_order WHERE seller_id = #{sellerId} AND created_at >= #{since}")
    long countSince(@Param("sellerId") Long sellerId, @Param("since") LocalDateTime since);

    @Select("SELECT COALESCE(SUM(COALESCE(deal_price, 0) * COALESCE(quantity, 1)), 0) "
            + "FROM meetup_order WHERE seller_id = #{sellerId}")
    BigDecimal sumGmv(@Param("sellerId") Long sellerId);

    @Select("SELECT COALESCE(SUM(COALESCE(deal_price, 0) * COALESCE(quantity, 1)), 0) "
            + "FROM meetup_order WHERE seller_id = #{sellerId} AND created_at >= #{since}")
    BigDecimal sumGmvSince(@Param("sellerId") Long sellerId, @Param("since") LocalDateTime since);

    @Select("SELECT COUNT(*) FROM meetup_order WHERE seller_id = #{sellerId} AND status = #{status}")
    long countByStatus(@Param("sellerId") Long sellerId, @Param("status") int status);
}
