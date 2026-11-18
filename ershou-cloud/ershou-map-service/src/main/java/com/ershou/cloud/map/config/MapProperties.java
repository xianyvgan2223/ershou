package com.ershou.cloud.map.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * 地图服务业务参数（Nacos dataId=ershou-map-service.yaml 的 {@code ershou.map.*} 段）。
 *
 * <p>演示点：改 Nacos 里的参数后 {@code @RefreshScope} 下一请求生效，无需重启。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "ershou.map")
public class MapProperties {

    /** 是否有缓存开关（本地想直连 DB 对比时可在 Nacos 关掉） */
    private boolean cacheEnabled = true;

    /** 点位基础列表（trade_point 本库数据）缓存秒数：只缓存点位本身，商品数每次实时/短缓存取 */
    private int cacheSeconds = 60;

    /** 点位商品统计（Feign item-service 结果）缓存秒数：短 TTL 保证"下单后地图数量"尽快更新 */
    private int itemStatsCacheSeconds = 10;

    /** Redis GEO 索引 TTL（小时） */
    private int geoIndexHours = 6;

    /** GEO 重建锁 TTL（秒），防止并发请求同时全量重建 */
    private int geoRebuildLockSeconds = 30;

    /**
     * 空点位清理定时任务开关。**默认关闭**：
     * 当前 item-service 的 count-by-point 只返回"在售(status=2)"数，拿不到"还有戏(0/1/2/3)"口径，
     * 若自动执行可能把"只挂着审核中/已锁定商品"的老点位误判为空并删除（遗留，见报告）。
     * 待 item-service 补齐活跃口径（或 map-service 改调活跃统计）后再在 Nacos 打开。
     * admin inner {@code POST /inner/points/cleanup-empty} 不受此开关限制（运营显式触发）。
     */
    private boolean cleanupEnabled = false;

    /** 空点位清理 cron（默认每天 03:10，与单体一致） */
    private String cleanupCron = "0 10 3 * * ?";

    /** 空点位宽限期（天）：刚建的点位可能还没挂商品 */
    private int cleanupGraceDays = 7;

    /**
     * 校区底图配置来自 user-service（campus 表在 ershou_user 库，不跨库 SQL）。
     * user-service 当前只有 {@code /api/v1/schools/{schoolId}/campuses}，先按「默认学校」拉全量校区再按 id 选；
     * 若之后有了 {@code /inner/campuses?schoolId=} 或按 id 查询，改 UserClient 即可（本服务无需改调用方）。
     */
    private long defaultSchoolId = 1;

    /** 校区配置内存缓存秒数（底图配置极少变） */
    private int campusCacheSeconds = 600;

    /**
     * 校区配置**拉取失败**后的重试间隔秒数（负缓存不能跟成功缓存一样长）：
     * user-service 往往比 map-service 晚就绪，若把失败也缓存 10 分钟，地图会长时间退化成自绘平面图。
     */
    private int campusFailureRetrySeconds = 10;

    /** 附近点位默认/最大半径（米）与默认/最大条数：与单体 TradePointService.nearbyPoints 的夹取口径一致 */
    private int nearbyDefaultRadius = 500;
    private int nearbyMaxRadius = 5000;
    private int nearbyDefaultLimit = 20;
    private int nearbyMaxLimit = 50;
}
