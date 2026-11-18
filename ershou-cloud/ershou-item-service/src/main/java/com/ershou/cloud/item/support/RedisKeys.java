package com.ershou.cloud.item.support;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Redis key 统一出口（开发文档 6.1：ershou:{模块}:{业务}:{标识}，全小写、冒号分隔）。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static String smsCode(String phone) {
        return "ershou:sms:code:" + phone;
    }

    public static String smsLimit(String phone) {
        return "ershou:sms:limit:" + phone + ":" + today();
    }

    public static String publishLimit(Long userId) {
        return "ershou:limit:post:" + userId + ":" + today();
    }

    public static String chatLimit(Long userId) {
        return "ershou:limit:chat:" + userId + ":" + today();
    }

    public static String itemLock(Long itemId) {
        return "ershou:item:lock:" + itemId;
    }

    public static String verifyCode(Long orderId) {
        return "ershou:meetup:code:" + orderId;
    }

    /** 商品详情缓存（JSON） */
    public static String itemDetail(Long itemId) {
        return "ershou:item:detail:" + itemId;
    }

    /** 商品不存在的空值缓存，防穿透 */
    public static String itemNull(Long itemId) {
        return "ershou:item:null:" + itemId;
    }

    /** 浏览量增量计数：先落 Redis，由定时任务批量回写 item.view_count */
    public static String itemView(Long itemId) {
        return "ershou:item:view:" + itemId;
    }

    /** 有未回写浏览量的商品 id 集合（HINCR 只能遍历 key，用 Set 记录待回写清单） */
    public static String itemViewDirty() {
        return "ershou:item:view:dirty";
    }

    /** 地图点位缓存版本号：写路径自增它即可让所有校区的点位缓存整体失效（常驻 key，同水位） */
    public static String mapPointsVersion() {
        return "ershou:map:points:ver";
    }

    /** 校区点位 + 每个点位在售数 / 最低价（JSON），带版本号 */
    public static String mapPoints(Long campusId, long version) {
        return "ershou:map:points:" + campusId + ":v" + version;
    }

    /**
     * 同上，但**只含"有商品"的点位**（地图展示用，见 TradePointService#listByCampus）。
     *
     * <p>为什么要单独一个 key：地图只画有商品的点位（地图是用来找货的），
     * 而发布页选点必须含 0 商品的点位（不然用户刚建的点位选不到）。
     * 两种可见性结果不同，共用一份缓存必然有一边是错的。
     */
    public static String mapPointsWithItems(Long campusId, long version) {
        return "ershou:map:points:" + campusId + ":items:v" + version;
    }

    public static String dictCategory(Long categoryId) {
        return "ershou:dict:category:" + categoryId;
    }

    /**
     * 校区点位的地理位置索引（Redis GEO，member = pointId）。
     *
     * <p>用途：按坐标找附近点位（"我周围 300 米有哪些面交点"），
     * 全程走 Redis，**不消耗任何高德配额**。
     *
     * <p>只放有经纬度的点位（自绘底图校区的像素坐标不是地理坐标，放进来算出来的是错的）。
     * 与 {@code ershou:map:points:*} 一样用版本号失效——写点位时 bump 版本号，
     * key 里的版本号一变，旧索引立刻不可达，由下次查询按 DB 重建。
     */
    public static String mapGeo(Long campusId, long version) {
        return "ershou:map:geo:" + campusId + ":v" + version;
    }

    /** 附近点位是否已有实例在重建（避免并发查询一起全量重建） */
    public static String mapGeoRebuildLock(Long campusId) {
        return "ershou:map:geo:rebuild:" + campusId;
    }

    public static String dictPoint(Long pointId) {
        return "ershou:dict:point:" + pointId;
    }

    public static String dictSchool(Long schoolId) {
        return "ershou:dict:school:" + schoolId;
    }

    public static String dictCampus(Long campusId) {
        return "ershou:dict:campus:" + campusId;
    }

    /** 私聊未读快照：Hash，field=sessionId，value=未读数（DB 为权威，这里是顶栏红点的快速读取路径） */
    public static String chatUnread(Long userId) {
        return "ershou:chat:unread:" + userId;
    }

    /** 私聊在线心跳：连接建立与 ping 时刷新 TTL */
    public static String chatOnline(Long userId) {
        return "ershou:chat:online:" + userId;
    }

    /** 私聊跨实例广播频道（Pub/Sub） */
    public static String chatChannel() {
        return "ershou:chat:channel:msg";
    }

    /** 运营概览统计缓存 */
    public static String adminDashboard() {
        return "ershou:admin:dashboard";
    }

    /** 上架审核跨实例广播频道（Pub/Sub）：新商品上架 / 被处置时通知所有审核员 */
    public static String adminReviewChannel() {
        return "ershou:admin:review:channel";
    }

    /** 上架审核：正在看审核页的审核员心跳（field=userId，value=昵称，过期即离线） */
    public static String adminReviewOnline() {
        return "ershou:admin:review:online";
    }

    /** ES 商品索引的增量同步水位（epoch millis） */
    public static String itemIndexWatermark() {
        return "ershou:item:index:watermark";
    }

    /** RabbitMQ order.created 消费幂等：按 orderId SETNX（重复投递不重复累加 sale_count） */
    public static String mqOrder(Long orderId) {
        return "ershou:item:mq:order:" + orderId;
    }

    private static String today() {
        return LocalDate.now().format(DAY);
    }
}
