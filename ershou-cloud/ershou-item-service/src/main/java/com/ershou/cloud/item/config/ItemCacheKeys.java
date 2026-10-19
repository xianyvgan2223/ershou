package com.ershou.cloud.item.config;

/**
 * item-service 在 Redis **db2** 下的业务 key 集中出口（设计 2.2：所有 key 必须带 {@code ershou:item:} 前缀）。
 *
 * <p>与单体 {@code common/util/RedisKeys} 中同名 key 的字符串**保持一致**（{@code ershou:item:detail:{id}} 等），
 * 这样后续把单体读路径切到本服务时缓存可以平滑复用；但 **db 不同**（单体 db0 / 本服务 db2），不互相污染。
 */
public final class ItemCacheKeys {

    private ItemCacheKeys() {
    }

    /** 商品详情缓存（JSON，TTL 见 ershou.item.detail-cache-seconds，默认 60s） */
    public static String itemDetail(Long itemId) {
        return "ershou:item:detail:" + itemId;
    }

    /** 商品浏览量增量（INCR，详情每次访问 +1；本轮演示不做批量回写，DB view_count 只读作基线） */
    public static String itemView(Long itemId) {
        return "ershou:item:view:" + itemId;
    }

    /** MQ 消费幂等：按 orderId SETNX（SET NX EX 24h），重复投递不再累加 sale_count */
    public static String mqOrder(Long orderId) {
        return "ershou:item:mq:order:" + orderId;
    }
}
