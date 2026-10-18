package com.ershou.campus.module.platform.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.cache.JsonCache;
import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import com.ershou.campus.common.util.TxHooks;
import com.ershou.campus.module.platform.config.PlatformCacheKeys;
import com.ershou.campus.module.platform.dto.PointSaveDTO;
import com.ershou.campus.module.platform.entity.PlatformConsignPoint;
import com.ershou.campus.module.platform.mapper.PlatformConsignPointMapper;
import com.ershou.campus.module.platform.vo.PlatformPointVO;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 平台代卖 / 自提点服务（设计 3.2 公开读 + 3.4 管理后台 CRUD）。
 *
 * <p>公开列表走 Redis 缓存（TTL 10min，写路径即删）；后台列表不走缓存，运营改完要立刻看到。
 * 删除是<b>软删</b>（status=0）：历史商品的 platform_point_id 还要能查到点位名。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformPointService {

    /**
     * 代卖点列表缓存 TTL。
     *
     * <p>设计写的是 10min；这里取 9min 是给 {@link JsonCache} 的"TTL +0~10% 抖动"留余量，
     * 保证 Redis 里实际 TTL 不超过设计上限（QA verify-platform-redis.ps1 R5-1 按上限断言）。
     * 语义上只会更早过期、不会更晚，写路径也会主动删缓存。
     */
    private static final Duration POINTS_TTL = Duration.ofSeconds(540);
    private static final TypeReference<List<PlatformPointVO>> POINTS_TYPE = new TypeReference<>() {
    };
    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final PlatformConsignPointMapper pointMapper;
    private final CampusMapper campusMapper;
    private final JsonCache jsonCache;
    private final PlatformCacheService cacheService;

    // ---------------------------------------------------------------- 公开读

    /** 用户端：只返回启用中的点位，按 sort,id（设计 3.2），带 10 分钟缓存 */
    public List<PlatformPointVO> listPublic(Long campusId) {
        Long keyCampus = campusId == null ? 0L : campusId;
        return jsonCache.getOrLoad(PlatformCacheKeys.points(keyCampus), POINTS_TTL, POINTS_TYPE,
                () -> toVOList(pointMapper.selectList(Wrappers.<PlatformConsignPoint>lambdaQuery()
                        .eq(campusId != null, PlatformConsignPoint::getCampusId, campusId)
                        .eq(PlatformConsignPoint::getStatus, STATUS_ENABLED)
                        .orderByAsc(PlatformConsignPoint::getSort)
                        .orderByAsc(PlatformConsignPoint::getId))));
    }

    // ---------------------------------------------------------------- 后台读

    /** 运营后台：全部点位（含停用），campusId 不传表示全部校区 */
    public List<PlatformPointVO> listAdmin(Long campusId) {
        return toVOList(pointMapper.selectList(Wrappers.<PlatformConsignPoint>lambdaQuery()
                .eq(campusId != null, PlatformConsignPoint::getCampusId, campusId)
                .orderByAsc(PlatformConsignPoint::getSort)
                .orderByAsc(PlatformConsignPoint::getId)));
    }

    // ---------------------------------------------------------------- 后台写

    @Transactional(rollbackFor = Exception.class)
    public PlatformPointVO create(PointSaveDTO dto) {
        validate(dto, true);
        PlatformConsignPoint point = new PlatformConsignPoint();
        apply(point, dto, true);
        pointMapper.insert(point);
        TxHooks.afterCommit(() -> cacheService.evictPointCaches(point.getCampusId()));
        log.info("[platform][point][create][{}] campus={} name={}", point.getId(), point.getCampusId(), point.getName());
        return toVO(point, campusName(point.getCampusId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public PlatformPointVO update(Long pointId, PointSaveDTO dto) {
        validate(dto, false);
        PlatformConsignPoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代卖点不存在");
        }
        Long oldCampusId = before.getCampusId();
        // 编辑允许不传 campusId（沿用原点位校区）
        Long campusId = dto.getCampusId() != null ? dto.getCampusId() : oldCampusId;
        PlatformConsignPoint update = new PlatformConsignPoint();
        update.setId(pointId);
        apply(update, dto, false);
        update.setCampusId(campusId);
        pointMapper.updateById(update);
        // 校区可能被改：新老校区的缓存都要失效
        TxHooks.afterCommit(() -> {
            cacheService.evictPointCaches(campusId);
            if (!Objects.equals(oldCampusId, campusId)) {
                cacheService.evictPointCaches(oldCampusId);
            }
        });
        log.info("[platform][point][update][{}] campus={}->{}", pointId, oldCampusId, campusId);
        return toVO(pointMapper.selectById(pointId), campusName(campusId));
    }

    /** 软删：status=0（设计 3.4，历史商品引用不破坏） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long pointId) {
        PlatformConsignPoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代卖点不存在");
        }
        PlatformConsignPoint update = new PlatformConsignPoint();
        update.setId(pointId);
        update.setStatus(STATUS_DISABLED);
        pointMapper.updateById(update);
        TxHooks.afterCommit(() -> cacheService.evictPointCaches(before.getCampusId()));
        log.info("[platform][point][delete][{}] campus={} 软删", pointId, before.getCampusId());
    }

    /** 启停：status 只接受 0/1 */
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long pointId, Integer status) {
        if (status == null || (status != STATUS_ENABLED && status != STATUS_DISABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位状态只能是 0（停用）或 1（启用）");
        }
        PlatformConsignPoint before = pointMapper.selectById(pointId);
        if (before == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "代卖点不存在");
        }
        PlatformConsignPoint update = new PlatformConsignPoint();
        update.setId(pointId);
        update.setStatus(status);
        pointMapper.updateById(update);
        TxHooks.afterCommit(() -> cacheService.evictPointCaches(before.getCampusId()));
        log.info("[platform][point][status][{}] {} -> {}", pointId, before.getStatus(), status);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 入参业务校验（Bean Validation 已拦了 name/address/经纬度范围）。
     *
     * @param requireCampus true=新建必须传 campusId；false=编辑可省略（沿用原点位校区）
     */
    private void validate(PointSaveDTO dto, boolean requireCampus) {
        if (requireCampus && dto.getCampusId() == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "校区不能为空");
        }
        if (!StringUtils.hasText(dto.getName())) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位名称不能为空");
        }
        if (!StringUtils.hasText(dto.getAddress())) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位地址不能为空");
        }
        BigDecimal lng = dto.getLng();
        BigDecimal lat = dto.getLat();
        if ((lng == null) != (lat == null)) {
            throw new BizException(ResultCode.PARAM_ERROR, "经纬度要么都填，要么都不填");
        }
        if (dto.getStatus() != null && dto.getStatus() != STATUS_ENABLED && dto.getStatus() != STATUS_DISABLED) {
            throw new BizException(ResultCode.PARAM_ERROR, "点位状态只能是 0（停用）或 1（启用）");
        }
    }

    /**
     * 表单字段映射。
     *
     * @param create true=新建（sort/status 缺省补 0/1）；false=编辑（缺省字段不覆盖已有值，
     *               避免运营只改地址时把已停用的点位重新启用、或把排序清零）
     */
    private void apply(PlatformConsignPoint point, PointSaveDTO dto, boolean create) {
        if (dto.getCampusId() != null) {
            point.setCampusId(dto.getCampusId());
        }
        point.setName(dto.getName().trim());
        point.setAddress(dto.getAddress().trim());
        point.setLng(dto.getLng());
        point.setLat(dto.getLat());
        point.setOpenHours(trimToNull(dto.getOpenHours()));
        point.setContactPhone(trimToNull(dto.getContactPhone()));
        point.setGuideText(trimToNull(dto.getGuideText()));
        point.setCoverUrl(trimToNull(dto.getCoverUrl()));
        if (dto.getSort() != null) {
            point.setSort(dto.getSort());
        } else if (create) {
            point.setSort(0);
        }
        if (dto.getStatus() != null) {
            point.setStatus(dto.getStatus());
        } else if (create) {
            point.setStatus(STATUS_ENABLED);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<PlatformPointVO> toVOList(List<PlatformConsignPoint> points) {
        if (points.isEmpty()) {
            return List.of();
        }
        Map<Long, String> campusNames = campusNames(points.stream()
                .map(PlatformConsignPoint::getCampusId).filter(Objects::nonNull).distinct().toList());
        List<PlatformPointVO> result = new ArrayList<>(points.size());
        for (PlatformConsignPoint point : points) {
            result.add(toVO(point, campusNames.get(point.getCampusId())));
        }
        return result;
    }

    private PlatformPointVO toVO(PlatformConsignPoint point, String campusName) {
        PlatformPointVO vo = new PlatformPointVO();
        vo.setId(point.getId());
        vo.setCampusId(point.getCampusId());
        vo.setCampusName(campusName);
        vo.setName(point.getName());
        vo.setAddress(point.getAddress());
        vo.setLng(point.getLng());
        vo.setLat(point.getLat());
        vo.setOpenHours(point.getOpenHours());
        vo.setContactPhone(point.getContactPhone());
        vo.setGuideText(point.getGuideText());
        vo.setCoverUrl(point.getCoverUrl());
        vo.setSort(point.getSort());
        vo.setStatus(point.getStatus());
        vo.setCreatedAt(point.getCreatedAt());
        vo.setUpdatedAt(point.getUpdatedAt());
        return vo;
    }

    private String campusName(Long campusId) {
        return campusNameMap(campusId == null ? List.of() : List.of(campusId)).get(campusId);
    }

    private Map<Long, String> campusNames(Collection<Long> campusIds) {
        return campusNameMap(campusIds);
    }

    private Map<Long, String> campusNameMap(Collection<Long> campusIds) {
        if (campusIds == null || campusIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Campus campus : campusMapper.selectBatchIds(campusIds)) {
            names.put(campus.getId(), campus.getName());
        }
        return names;
    }
}
