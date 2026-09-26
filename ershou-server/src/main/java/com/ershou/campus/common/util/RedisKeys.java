package com.ershou.campus.common.util;

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

    public static String dictCategory(Long categoryId) {
        return "ershou:dict:category:" + categoryId;
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

    /** ES 商品索引的增量同步水位（epoch millis） */
    public static String itemIndexWatermark() {
        return "ershou:item:index:watermark";
    }

    private static String today() {
        return LocalDate.now().format(DAY);
    }
}
