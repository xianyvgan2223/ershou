package com.ershou.campus.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

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

    public static String itemDetail(Long itemId) {
        return "ershou:item:detail:" + itemId;
    }

    /** ES 商品索引的增量同步水位（epoch millis） */
    public static String itemIndexWatermark() {
        return "ershou:item:index:watermark";
    }

    private static String today() {
        return LocalDate.now().format(DAY);
    }
}
