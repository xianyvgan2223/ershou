package com.ershou.campus.module.platform.mapper;

import com.ershou.campus.module.platform.entity.PlatformItemRow;
import com.ershou.campus.module.platform.vo.AdminPlatformItemVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台商品对 item 表的<b>自有 SQL</b>（设计 3.1）。
 *
 * <p>为什么不复用 item 模块的 {@code ItemMapper}：平台代卖的读写口径由本模块负责
 * （发布直插 seller_type=2、专区列表按平台点/分类筛选、秒杀价 join），
 * 放在这里可以完全不改 item 模块的代码，也不会在并行开发时互相等编译。
 *
 * <p>约定：只读写 {@code seller_type=2} 的行；个人商品（1）一律不碰。
 */
@Mapper
public interface PlatformItemMapper {

    /** 列表/详情共用的查询列（含分类名、代卖点名两个 join 出来的展示列） */
    String SELECT_COLUMNS = "SELECT i.id, i.seller_id, i.school_id, i.campus_id, i.title, i.description, "
            + "i.category_id, i.condition_level, i.price, i.original_price, i.negotiable, i.stock, "
            + "i.is_unique, i.cover_url, i.images, i.trade_mode, i.prefer_point_id, i.seller_type, "
            + "i.platform_point_id, i.status, i.expire_at, i.published_at, i.created_at, "
            + "c.name AS category_name, p.name AS point_name "
            + "FROM item i "
            + "LEFT JOIN item_category c ON c.id = i.category_id "
            + "LEFT JOIN platform_consign_point p ON p.id = i.platform_point_id ";

    /** 按 id 取一件平台商品（不存在 / 不是平台商品返回 null） */
    @Select(SELECT_COLUMNS + "WHERE i.id = #{itemId} AND i.seller_type = 2")
    PlatformItemRow selectPlatformItem(@Param("itemId") Long itemId);

    /** 批量取平台商品（秒杀场次里的商品信息） */
    @Select("<script>" + SELECT_COLUMNS
            + "WHERE i.seller_type = 2 AND i.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<PlatformItemRow> selectPlatformItemsByIds(@Param("ids") List<Long> ids);

    /**
     * 平台专区商品分页（只返回 seller_type=2 且 status=2 在售）。
     * sort 只接受 latest / price_asc / price_desc（其它值按 latest 处理，不存在 SQL 注入）。
     */
    @Select("<script>" + SELECT_COLUMNS
            + "WHERE i.seller_type = 2 AND i.status = 2 "
            + "<if test='campusId != null'> AND i.campus_id = #{campusId}</if>"
            + "<if test='pointId != null'> AND i.platform_point_id = #{pointId}</if>"
            + "<if test='categoryId != null'> AND i.category_id = #{categoryId}</if>"
            + "<choose>"
            + "<when test=\"sort == 'price_asc'\"> ORDER BY i.price ASC, i.id DESC</when>"
            + "<when test=\"sort == 'price_desc'\"> ORDER BY i.price DESC, i.id DESC</when>"
            + "<otherwise> ORDER BY i.published_at DESC, i.id DESC</otherwise>"
            + "</choose>"
            + " LIMIT #{offset}, #{limit}"
            + "</script>")
    List<PlatformItemRow> selectPlatformItems(@Param("campusId") Long campusId,
                                              @Param("pointId") Long pointId,
                                              @Param("categoryId") Long categoryId,
                                              @Param("sort") String sort,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /** 同条件的总数（分页用，条件必须与 selectPlatformItems 保持一致） */
    @Select("<script>SELECT COUNT(*) FROM item i WHERE i.seller_type = 2 AND i.status = 2 "
            + "<if test='campusId != null'> AND i.campus_id = #{campusId}</if>"
            + "<if test='pointId != null'> AND i.platform_point_id = #{pointId}</if>"
            + "<if test='categoryId != null'> AND i.category_id = #{categoryId}</if>"
            + "</script>")
    long countPlatformItems(@Param("campusId") Long campusId,
                            @Param("pointId") Long pointId,
                            @Param("categoryId") Long categoryId);

    /** 专区概览：在售平台商品数 */
    @Select("SELECT COUNT(*) FROM item WHERE seller_type = 2 AND status = 2 AND campus_id = #{campusId}")
    long countOnSaleByCampus(@Param("campusId") Long campusId);

    /**
     * 发布平台商品（管理后台）。
     *
     * <p>直插的关键值全部在这里写死或由调用方按设计填好：
     * {@code seller_type=2}、{@code audit_result=1}（免审核）、{@code status=2}（直接上架）。
     * 有效期 / 上架时间由 service 算好传入；{@code last_live_*} 同步写成当前内容（审核快照口径一致）。
     */
    @Insert("INSERT INTO item (seller_id, school_id, campus_id, seller_type, platform_point_id, "
            + "title, description, category_id, condition_level, price, original_price, negotiable, "
            + "stock, is_unique, cover_url, images, trade_mode, prefer_point_id, status, audit_result, "
            + "expire_at, published_at, last_live_title, last_live_desc_hash, view_count, chat_count, fav_count, version) "
            + "VALUES (#{sellerId}, #{schoolId}, #{campusId}, #{sellerType}, #{platformPointId}, "
            + "#{title}, #{description}, #{categoryId}, #{conditionLevel}, #{price}, #{originalPrice}, #{negotiable}, "
            + "#{stock}, #{isUnique}, #{coverUrl}, #{images}, #{tradeMode}, #{preferPointId}, #{status}, 1, "
            + "#{expireAt}, #{publishedAt}, #{title}, "
            + "IF(#{description} IS NULL OR #{description} = '', NULL, MD5(#{description})), "
            + "0, 0, 0, 0)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlatformItem(PlatformItemRow row);

    // ================================================================
    // 第 3 轮 · 运营台管理 SQL（设计 3.2 / 3.8）
    // ================================================================

    /** 运营台商品列表列：含代卖点名、浏览量、有效期，对应 AdminPlatformItemVO */
    String ADMIN_SELECT_COLUMNS = "SELECT i.id, i.title, i.price, i.original_price, i.cover_url, i.stock, "
            + "i.status, i.platform_point_id AS point_id, p.name AS point_name, i.seller_type, "
            + "i.view_count, i.published_at, i.expire_at "
            + "FROM item i "
            + "LEFT JOIN platform_consign_point p ON p.id = i.platform_point_id ";

    /** 按 id 取运营台视角的单件平台商品（含全部状态） */
    @Select(ADMIN_SELECT_COLUMNS + "WHERE i.id = #{itemId} AND i.seller_type = 2")
    AdminPlatformItemVO selectAdminItem(@Param("itemId") Long itemId);

    /** 运营台平台商品分页：**含全部状态**，keyword 匹配 title（服务层已把空串归一成 null） */
    @Select("<script>" + ADMIN_SELECT_COLUMNS
            + "WHERE i.seller_type = 2 "
            + "<if test='campusId != null'> AND i.campus_id = #{campusId}</if>"
            + "<if test='pointId != null'> AND i.platform_point_id = #{pointId}</if>"
            + "<if test='status != null'> AND i.status = #{status}</if>"
            + "<if test='keyword != null'> AND i.title LIKE CONCAT('%', #{keyword}, '%')</if>"
            + " ORDER BY i.id DESC LIMIT #{offset}, #{limit}"
            + "</script>")
    List<AdminPlatformItemVO> selectAdminItems(@Param("campusId") Long campusId,
                                               @Param("pointId") Long pointId,
                                               @Param("status") Integer status,
                                               @Param("keyword") String keyword,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit);

    /** 与 selectAdminItems 同条件的总数 */
    @Select("<script>SELECT COUNT(*) FROM item i WHERE i.seller_type = 2 "
            + "<if test='campusId != null'> AND i.campus_id = #{campusId}</if>"
            + "<if test='pointId != null'> AND i.platform_point_id = #{pointId}</if>"
            + "<if test='status != null'> AND i.status = #{status}</if>"
            + "<if test='keyword != null'> AND i.title LIKE CONCAT('%', #{keyword}, '%')</if>"
            + "</script>")
    long countAdminItems(@Param("campusId") Long campusId,
                         @Param("pointId") Long pointId,
                         @Param("status") Integer status,
                         @Param("keyword") String keyword);

    /** 概览：平台商品总数（含全部状态，按校区） */
    @Select("SELECT COUNT(*) FROM item WHERE seller_type = 2 AND campus_id = #{campusId}")
    long countByCampus(@Param("campusId") Long campusId);

    /** 概览：在售平台商品里库存 <= threshold 的数量（库存告警） */
    @Select("SELECT COUNT(*) FROM item WHERE seller_type = 2 AND status = 2 "
            + "AND campus_id = #{campusId} AND stock <= #{threshold}")
    long countLowStockOnSale(@Param("campusId") Long campusId, @Param("threshold") int threshold);

    /**
     * 运营台编辑平台商品：只更新传入的字段（null=不动）。
     * 改库存时同步 is_unique（>1 视为多库存非孤品），与发布口径一致。
     */
    @Update("<script>UPDATE item SET version = version + 1"
            + "<if test='title != null'>, title = #{title}</if>"
            + "<if test='price != null'>, price = #{price}</if>"
            + "<if test='stock != null'>, stock = #{stock}, "
            // XML 里不能直接写 "<="，用 "> 1 THEN 0 ELSE 1" 等价表达（stock>1 → 非孤品）
            + "is_unique = CASE WHEN #{stock} > 1 THEN 0 ELSE 1 END</if>"
            + "<if test='platformPointId != null'>, platform_point_id = #{platformPointId}</if>"
            + " WHERE id = #{itemId} AND seller_type = 2"
            + "</script>")
    int updateAdminItem(@Param("itemId") Long itemId,
                        @Param("title") String title,
                        @Param("price") java.math.BigDecimal price,
                        @Param("stock") Integer stock,
                        @Param("platformPointId") Long platformPointId);

    /** 运营台下架：status=5（设计 3.2）。只允许平台商品。 */
    @Update("UPDATE item SET status = 5, version = version + 1 WHERE id = #{itemId} AND seller_type = 2")
    int offlineItem(@Param("itemId") Long itemId);

    /** 运营台重新上架：status=2 + 有效期重新给 30 天（设计 3.2） */
    @Update("UPDATE item SET status = 2, expire_at = #{expireAt}, "
            + "published_at = COALESCE(published_at, NOW()), version = version + 1 "
            + "WHERE id = #{itemId} AND seller_type = 2")
    int onlineItem(@Param("itemId") Long itemId, @Param("expireAt") LocalDateTime expireAt);

    /**
     * 场次加秒杀商品时保证 {@code item.stock >= seckill_stock}（第 2 轮迁移第 6 节同口径），
     * 否则第 1 人抢完就把孤品打到 stock=0/status=4，多份秒杀退化。
     */
    @Update("UPDATE item SET stock = GREATEST(stock, #{stock}), "
            + "is_unique = CASE WHEN GREATEST(stock, #{stock}) <= 1 THEN 1 ELSE 0 END, version = version + 1 "
            + "WHERE id = #{itemId} AND seller_type = 2")
    int raiseStockForSeckill(@Param("itemId") Long itemId, @Param("stock") int stock);
}
