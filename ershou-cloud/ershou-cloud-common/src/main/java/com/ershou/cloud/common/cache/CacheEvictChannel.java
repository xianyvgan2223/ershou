package com.ershou.cloud.common.cache;

/**
 * 本地缓存（L2）失效广播：Redis Pub/Sub 频道与消息格式。
 *
 * <p>消息格式：{@code scope|key}，例如 {@code item|ershou:item:detail:123}。
 * {@code scope|*} 表示清空该 scope 的全部本地缓存（地图版本整体失效时使用）。
 * Redis key 只含字母/数字/冒号/下划线，不含 {@code |}，所以用第一个 {@code |} 切分即可。
 */
public final class CacheEvictChannel {

    public static final String NAME = "ershou:cache:evict";

    public static final String SCOPE_ITEM = "item";
    public static final String SCOPE_MAP = "map";
    public static final String SCOPE_PLATFORM = "platform";

    /** 清空当前 scope 所有本地缓存 */
    public static final String ALL_KEYS = "*";

    private CacheEvictChannel() {
    }

    public static String message(String scope, String key) {
        return scope + "|" + key;
    }
}
