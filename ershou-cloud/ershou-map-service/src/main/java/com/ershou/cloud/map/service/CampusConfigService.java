package com.ershou.cloud.map.service;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.map.config.MapProperties;
import com.ershou.cloud.map.feign.CampusVO;
import com.ershou.cloud.map.feign.UserClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 校区底图配置（跨服务只读：user-service 的 campus 表）。
 *
 * <p>单体里 {@code /map/points} 直接读 {@code dict.campus(campusId)} 拿 {@code mapType/mapConfig}；
 * 拆库后 campus 归属 `ershou_user`，本服务**不跨库 SQL**，改走 Feign 并做内存缓存：
 * <ul>
 *   <li>一次拉该学校全部校区（user-service 现有公开接口），按 campusId 选；</li>
 *   <li>user-service 不可用时返回 null → 调用方降级为 {@code mapType=1/mapConfig=null}（自绘平面图），
 *       地图页不至于 500；</li>
 *   <li>缓存默认 10 分钟（底图配置极少变），Nacos {@code ershou.map.campus-cache-seconds} 可调。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampusConfigService {

    private final UserClient userClient;
    private final MapProperties props;
    private final ObjectMapper json;

    private volatile List<CampusVO> cache = Collections.emptyList();
    private volatile long loadedAt = 0L;
    /** 上一次拉取是否失败：失败时只用短 TTL（campusFailureRetrySeconds）重试，避免负缓存 10 分钟 */
    private volatile boolean lastLoadFailed = false;

    /** 按 campusId 取校区配置；取不到（下游不可用/不存在）返回 null，由调用方降级 */
    public CampusVO campus(Long campusId) {
        if (campusId == null) {
            return null;
        }
        for (CampusVO c : campuses()) {
            if (campusId.equals(c.getId())) {
                return c;
            }
        }
        return null;
    }

    /** campus.map_config（JSON 文本）→ 对象，保持与单体 MapDataVO.mapConfig 的 JSON 结构一致 */
    public Object parseMapConfig(CampusVO campus) {
        if (campus == null || !StringUtils.hasText(campus.getMapConfig())) {
            return null;
        }
        try {
            return json.readValue(campus.getMapConfig(), Object.class);
        } catch (Exception e) {
            log.warn("[map][campus][{}] mapConfig 解析失败：{}", campus.getId(), e.getMessage());
            return null;
        }
    }

    private List<CampusVO> campuses() {
        long successTtlMs = Math.max(0, props.getCampusCacheSeconds()) * 1000L;
        long failureTtlMs = Math.max(1, props.getCampusFailureRetrySeconds()) * 1000L;
        long ttlMs = lastLoadFailed ? failureTtlMs : successTtlMs;
        long now = System.currentTimeMillis();
        if (loadedAt > 0 && now - loadedAt < ttlMs) {
            return cache;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (loadedAt > 0 && now - loadedAt < ttlMs) {
                return cache;
            }
            boolean ok = false;
            try {
                Result<List<CampusVO>> result = userClient.campuses(props.getDefaultSchoolId());
                if (result != null && result.getCode() == ResultCode.SUCCESS.getCode() && result.getData() != null) {
                    cache = result.getData();
                    ok = true;
                } else {
                    log.warn("[map][campus] 拉取校区配置失败（{} 秒后重试）：code={}",
                            props.getCampusFailureRetrySeconds(), result == null ? "null" : result.getCode());
                }
            } catch (Exception e) {
                log.warn("[map][campus] 调用 user-service 异常（{} 秒后重试）：{}",
                        props.getCampusFailureRetrySeconds(), e.getMessage());
            }
            lastLoadFailed = !ok;
            loadedAt = now;
            return cache;
        }
    }
}
