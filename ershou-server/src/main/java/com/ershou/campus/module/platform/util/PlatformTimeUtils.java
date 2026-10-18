package com.ershou.campus.module.platform.util;

import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 运营台入参时间解析（第 3 轮设计 3.3 / 3.4：券与场次的时间由前端表单传入）。
 *
 * <p>为什么 DTO 用 String 而不是 LocalDateTime：前端 Element Plus 日期选择器常见输出
 * {@code yyyy-MM-dd HH:mm:ss}，而浏览器 {@code new Date().toJSON()} 又是 ISO（带 Z）。
 * 本项目其它模块（面交时间窗）同样用 String + 服务端解析的写法。
 * 这里统一兼容四种形态，避免"前端格式一变就 400"：
 * <ol>
 *   <li>{@code 2026-10-18 10:00:00}（本项目最常见）</li>
 *   <li>{@code 2026-10-18T10:00:00}（ISO local）</li>
 *   <li>{@code 2026-10-18T02:00:00Z} / {@code +08:00}（带时区，换算成本机时区）</li>
 *   <li>{@code 2026-10-18T02:00:00.000Z}（毫秒精度）</li>
 * </ol>
 */
public final class PlatformTimeUtils {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PlatformTimeUtils() {
    }

    /** 解析运营台传入的时间；空串返回 null；无法识别抛参数异常 */
    public static LocalDateTime parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDateTime.parse(text, FMT);
        } catch (Exception ignored) {
            // 继续尝试下一种格式
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
            // 继续尝试下一种格式
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // 继续尝试下一种格式
        }
        try {
            return Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            throw new BizException(ResultCode.PARAM_ERROR, "时间格式不正确：" + raw);
        }
    }

    /** 需要回显/记录时统一成项目口径 */
    public static String format(LocalDateTime time) {
        return time == null ? null : FMT.format(time);
    }
}
