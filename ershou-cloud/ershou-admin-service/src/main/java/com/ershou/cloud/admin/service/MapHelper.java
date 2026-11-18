package com.ershou.cloud.admin.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feign 透传 Map 的取值工具：下游 Jackson 反序列化后数字可能是 Integer/Long/Double，
 * 直接强转会 ClassCastException；这里统一做宽松转换，字段缺失/类型不认识返回 null。
 */
public final class MapHelper {

    private static final DateTimeFormatter SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private MapHelper() {
    }

    public static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Long lng(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Integer intg(Map<String, Object> map, String key) {
        Long v = lng(map, key);
        return v == null ? null : v.intValue();
    }

    public static BigDecimal dec(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public static Boolean bool(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return null;
    }

    /** 时间兼容 'yyyy-MM-dd HH:mm:ss' / ISO（带 T），解析失败返回 null（前端容忍空） */
    public static LocalDateTime dt(Map<String, Object> map, String key) {
        String raw = str(map, key);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            if (text.length() > 19) {
                text = text.substring(0, 19);
            }
            if (text.indexOf('T') >= 0) {
                return LocalDateTime.parse(text, ISO);
            }
            if (text.length() == 10) {
                return LocalDate.parse(text).atStartOfDay();
            }
            return LocalDateTime.parse(text, SPACE);
        } catch (Exception e) {
            return null;
        }
    }

    /** 字符串数组（JSON 数组反序列化后是 List<Object>） */
    public static List<String> strList(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (!(v instanceof List<?> list)) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }
}
