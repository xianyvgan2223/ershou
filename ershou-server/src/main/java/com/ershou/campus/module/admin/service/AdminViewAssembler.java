package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.common.util.PhoneCrypto;
import com.ershou.campus.module.admin.vo.AdminItemVO;
import com.ershou.campus.module.admin.vo.AdminOrderVO;
import com.ershou.campus.module.admin.vo.AdminPointVO;
import com.ershou.campus.module.admin.vo.AdminUserVO;
import com.ershou.campus.module.item.entity.Item;
import com.ershou.campus.module.item.entity.ItemCategory;
import com.ershou.campus.module.item.mapper.ItemCategoryMapper;
import com.ershou.campus.module.item.service.ItemService;
import com.ershou.campus.module.map.entity.TradePoint;
import com.ershou.campus.module.map.mapper.TradePointMapper;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.service.OrderService;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.user.entity.SysUser;
import com.ershou.campus.module.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;

/**
 * 运营后台的视图装配：把商品/点位/用户/订单转成带"运营上下文"的 VO。
 * 分页场景统一批量取关联数据，避免 N+1。
 *
 * <p>关联 id 允许为 NULL：商品可以不选面交点、官方点位没有 creator、面交单可以不指定点位。
 * 注意 {@code Map.of()} 返回的不可变 Map 对 {@code get(null)} 会直接抛 NPE，
 * 所以这里的查找一律走 {@link #pick(Map, Object)}，装配用的 Map 也统一用 HashMap。
 */
@Component
@RequiredArgsConstructor
public class AdminViewAssembler {

    private final ItemCategoryMapper categoryMapper;
    private final TradePointMapper pointMapper;
    private final CampusMapper campusMapper;
    private final UserService userService;
    private final PhoneCrypto phoneCrypto;
    private final ObjectMapper json;

    public PageResult<AdminItemVO> itemPage(IPage<Item> page) {
        List<Item> records = page.getRecords();
        Map<Long, ItemCategory> categories = mapByIds(
                records.stream().map(Item::getCategoryId).toList(),
                ids -> categoryMapper.selectBatchIds(ids), ItemCategory::getId);
        Map<Long, TradePoint> points = mapByIds(
                records.stream().map(Item::getPreferPointId).toList(),
                ids -> pointMapper.selectBatchIds(ids), TradePoint::getId);
        Map<Long, Campus> campuses = mapByIds(
                records.stream().map(Item::getCampusId).toList(),
                ids -> campusMapper.selectBatchIds(ids), Campus::getId);
        Map<Long, SysUser> sellers = userService.mapByIds(records.stream().map(Item::getSellerId).toList());
        return PageResult.of(page, item -> toItemVO(item, categories, points, campuses, sellers));
    }

    public PageResult<AdminPointVO> pointPage(IPage<TradePoint> page) {
        List<TradePoint> records = page.getRecords();
        Map<Long, Campus> campuses = mapByIds(
                records.stream().map(TradePoint::getCampusId).toList(),
                ids -> campusMapper.selectBatchIds(ids), Campus::getId);
        Map<Long, SysUser> creators = userService.mapByIds(records.stream().map(TradePoint::getCreatorId).toList());
        return PageResult.of(page, point -> toPointVO(point, campuses, creators));
    }

    public PageResult<AdminOrderVO> orderPage(IPage<MeetupOrder> page) {
        List<MeetupOrder> records = page.getRecords();
        List<Long> userIds = new ArrayList<>();
        records.forEach(o -> {
            userIds.add(o.getBuyerId());
            userIds.add(o.getSellerId());
        });
        Map<Long, SysUser> users = userService.mapByIds(userIds);
        Map<Long, TradePoint> points = mapByIds(
                records.stream().map(MeetupOrder::getPointId).toList(),
                ids -> pointMapper.selectBatchIds(ids), TradePoint::getId);
        return PageResult.of(page, order -> toOrderVO(order, users, points));
    }

    public PageResult<AdminUserVO> userPage(IPage<SysUser> page) {
        return PageResult.of(page, this::toUserVO);
    }

    public AdminUserVO toUserVO(SysUser user) {
        AdminUserVO vo = new AdminUserVO();
        vo.setId(user.getId());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setPhone(maskPhone(user.getPhone()));
        vo.setSchoolId(user.getSchoolId());
        vo.setCampusId(user.getCampusId());
        vo.setShopName(user.getShopName());
        vo.setRoleFlags(user.getRoleFlags());
        vo.setIdentityFlag(user.getIdentityFlag());
        vo.setCreditScore(user.getCreditScore());
        vo.setCreditLevel(user.getCreditLevel());
        vo.setCreditLevelText(creditLevelText(user.getCreditLevel()));
        vo.setRiskScore(user.getRiskScore());
        vo.setStatus(user.getStatus());
        vo.setStatusText(userStatusText(user.getStatus()));
        vo.setBanUntil(user.getBanUntil());
        vo.setPublishLimit(user.getPublishLimit());
        vo.setChatLimit(user.getChatLimit());
        vo.setDeviceId(user.getDeviceId());
        vo.setDeviceAccountCount(user.getDeviceAccountCount());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    private AdminItemVO toItemVO(Item item, Map<Long, ItemCategory> categories,
                                 Map<Long, TradePoint> points, Map<Long, Campus> campuses,
                                 Map<Long, SysUser> sellers) {
        AdminItemVO vo = new AdminItemVO();
        vo.setId(item.getId());
        vo.setTitle(item.getTitle());
        vo.setDescription(item.getDescription());
        vo.setPrice(item.getPrice());
        vo.setOriginalPrice(item.getOriginalPrice());
        vo.setConditionLevel(item.getConditionLevel());
        vo.setConditionText(ItemService.conditionText(item.getConditionLevel()));
        vo.setCoverUrl(item.getCoverUrl());
        vo.setImages(readStringList(item.getImages()));
        vo.setCategoryId(item.getCategoryId());
        vo.setCampusId(item.getCampusId());
        vo.setPreferPointId(item.getPreferPointId());
        vo.setSellerId(item.getSellerId());
        vo.setStatus(item.getStatus());
        vo.setStatusText(ItemService.statusText(item.getStatus()));
        vo.setAuditResult(item.getAuditResult());
        vo.setAuditReason(item.getAuditReason());
        vo.setRejectCode(item.getRejectCode());
        vo.setViewCount(item.getViewCount());
        vo.setFavCount(item.getFavCount());
        vo.setStock(item.getStock());
        vo.setPublishedAt(item.getPublishedAt());
        vo.setExpireAt(item.getExpireAt());
        vo.setCreatedAt(item.getCreatedAt());
        ItemCategory category = pick(categories, item.getCategoryId());
        if (category != null) {
            vo.setCategoryName(category.getName());
        }
        TradePoint point = pick(points, item.getPreferPointId());
        if (point != null) {
            vo.setPreferPointName(point.getName());
        }
        Campus campus = pick(campuses, item.getCampusId());
        if (campus != null) {
            vo.setCampusName(campus.getName());
        }
        SysUser seller = pick(sellers, item.getSellerId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
            vo.setSellerPhone(maskPhone(seller.getPhone()));
            vo.setSellerCreditScore(seller.getCreditScore());
        }
        return vo;
    }

    private AdminPointVO toPointVO(TradePoint point, Map<Long, Campus> campuses, Map<Long, SysUser> creators) {
        AdminPointVO vo = new AdminPointVO();
        vo.setId(point.getId());
        vo.setCampusId(point.getCampusId());
        vo.setName(point.getName());
        vo.setSource(point.getSource());
        vo.setPointType(point.getPointType());
        vo.setCoordX(point.getCoordX());
        vo.setCoordY(point.getCoordY());
        vo.setSafetyScore(point.getSafetyScore());
        vo.setSafeTags(readStringList(point.getSafeTags()));
        vo.setGuideText(point.getGuideText());
        vo.setStatus(point.getStatus());
        vo.setAuditStatus(point.getAuditStatus());
        vo.setCreatorId(point.getCreatorId());
        vo.setCreatedAt(point.getCreatedAt());
        Campus campus = pick(campuses, point.getCampusId());
        if (campus != null) {
            vo.setCampusName(campus.getName());
        }
        SysUser creator = pick(creators, point.getCreatorId());
        if (creator != null) {
            vo.setCreatorNickname(creator.getNickname());
        }
        return vo;
    }

    private AdminOrderVO toOrderVO(MeetupOrder order, Map<Long, SysUser> users, Map<Long, TradePoint> points) {
        AdminOrderVO vo = new AdminOrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setBatchNo(order.getBatchNo());
        vo.setItemId(order.getItemId());
        vo.setItemTitle(order.getItemTitle());
        vo.setItemCover(order.getItemCover());
        vo.setQuantity(order.getQuantity());
        vo.setDealPrice(order.getDealPrice());
        vo.setBuyerId(order.getBuyerId());
        vo.setSellerId(order.getSellerId());
        vo.setPointId(order.getPointId());
        vo.setTimeSlotStart(order.getTimeSlotStart());
        vo.setTimeSlotEnd(order.getTimeSlotEnd());
        vo.setStatus(order.getStatus());
        vo.setStatusText(OrderService.statusText(order.getStatus()));
        vo.setVerifiedAt(order.getVerifiedAt());
        vo.setBuyerConfirmAt(order.getBuyerConfirmAt());
        vo.setSellerConfirmAt(order.getSellerConfirmAt());
        vo.setCancelReason(order.getCancelReason());
        vo.setCreatedAt(order.getCreatedAt());
        SysUser buyer = pick(users, order.getBuyerId());
        if (buyer != null) {
            vo.setBuyerNickname(buyer.getNickname());
        }
        SysUser seller = pick(users, order.getSellerId());
        if (seller != null) {
            vo.setSellerNickname(seller.getNickname());
        }
        TradePoint point = pick(points, order.getPointId());
        if (point != null) {
            vo.setPointName(point.getName());
        }
        return vo;
    }

    public String maskPhone(String encryptedPhone) {
        if (!StringUtils.hasText(encryptedPhone)) {
            return null;
        }
        try {
            return PhoneCrypto.mask(phoneCrypto.decrypt(encryptedPhone));
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> readStringList(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public String writeStringList(List<String> list) {
        try {
            return json.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static String userStatusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "限权";
            case 3 -> "封禁";
            default -> "未知";
        };
    }

    public static String creditLevelText(Integer level) {
        if (level == null) {
            return "";
        }
        return switch (level) {
            case 1 -> "优秀";
            case 2 -> "良好";
            case 3 -> "一般";
            case 4 -> "风险";
            default -> "未知";
        };
    }

    /** 关联 id 允许为 NULL，所以取关联对象必须 null-safe（Map.of() 的 get(null) 会抛 NPE） */
    private static <K, V> V pick(Map<K, V> map, K key) {
        return key == null ? null : map.get(key);
    }

    /** 批量装配关联数据：ids 里的 null 先剔除，没命中任何 id 时也要返回可 get(null) 的 Map */
    private <T> Map<Long, T> mapByIds(List<Long> rawIds, Function<List<Long>, List<T>> loader,
                                      Function<T, Long> idGetter) {
        Map<Long, T> map = new HashMap<>();
        List<Long> ids = rawIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return map;
        }
        for (T row : loader.apply(ids)) {
            map.putIfAbsent(idGetter.apply(row), row);
        }
        return map;
    }
}
