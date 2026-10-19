package com.ershou.cloud.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * id 工具（订单号/批次号/请求 id），与单体订单号格式保持兼容：
 * {@code yyyyMMddHHmmss + 4 位随机}，便于人眼识别与排查。
 */
public final class Ids {

    private static final DateTimeFormatter ORDER_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Ids() {
    }

    /** 32 位无连字符 UUID（trace/幂等键） */
    public static String uuid32() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 16 位 traceId（网关/服务日志） */
    public static String traceId() {
        return uuid32().substring(0, 16);
    }

    /** 面交单号：yyyyMMddHHmmss + 4 位随机数字 */
    public static String genOrderNo() {
        return LocalDateTime.now().format(ORDER_TS) + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /** 合并面交批次号：B + yyyyMMddHHmmss + 4 位随机数字 */
    public static String genBatchNo() {
        return "B" + LocalDateTime.now().format(ORDER_TS)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
