package com.ershou.cloud.admin.service;

import com.ershou.cloud.admin.client.FeignResults;
import com.ershou.cloud.admin.client.OrderClient;
import com.ershou.cloud.admin.vo.AdminOrderVO;
import com.ershou.cloud.common.result.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 面交单查询（BFF → order-service /inner/orders；只读，不改状态机）。
 *
 * <p>字段映射对齐 order-service 的 OrderVO：buyerName/sellerName → 单体的 buyerNickname/sellerNickname；
 * 单体有 buyerConfirmAt/sellerConfirmAt，order inner 只给 buyerConfirmed/sellerConfirmed 布尔，
 * 时间字段留空（前端展示用，见遗留）。
 */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrderClient orderClient;

    public PageResult<AdminOrderVO> page(Integer status, Long buyerId, Long sellerId, String keyword,
                                         long pageNum, long pageSize) {
        long size = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        // inner 只支持单一 userId（买家或卖家参与）；两个都传时按买家过滤，商品/时间等其余条件仍生效
        Long userId = buyerId != null ? buyerId : sellerId;
        PageResult<Map<String, Object>> res = FeignResults.data(
                orderClient.pageOrders(userId, status, keyword == null ? "" : keyword.trim(),
                        Math.max(pageNum, 1), size), "订单服务");
        if (res == null) {
            return PageResult.of(List.of(), 0, Math.max(pageNum, 1), size);
        }
        List<AdminOrderVO> list = res.getRecords() == null ? List.of()
                : res.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(list, res.getTotal(), res.getPageNum(), res.getPageSize());
    }

    private AdminOrderVO toVO(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        AdminOrderVO vo = new AdminOrderVO();
        vo.setId(MapHelper.lng(m, "id"));
        vo.setOrderNo(MapHelper.str(m, "orderNo"));
        vo.setBatchNo(MapHelper.str(m, "batchNo"));
        vo.setItemId(MapHelper.lng(m, "itemId"));
        vo.setItemTitle(MapHelper.str(m, "itemTitle"));
        vo.setItemCover(MapHelper.str(m, "itemCover"));
        vo.setQuantity(MapHelper.intg(m, "quantity"));
        vo.setDealPrice(MapHelper.dec(m, "dealPrice"));
        vo.setBuyerId(MapHelper.lng(m, "buyerId"));
        vo.setBuyerNickname(MapHelper.str(m, "buyerName"));
        vo.setSellerId(MapHelper.lng(m, "sellerId"));
        vo.setSellerNickname(MapHelper.str(m, "sellerName"));
        vo.setPointId(MapHelper.lng(m, "pointId"));
        vo.setPointName(MapHelper.str(m, "pointName"));
        vo.setTimeSlotStart(MapHelper.dt(m, "timeSlotStart"));
        vo.setTimeSlotEnd(MapHelper.dt(m, "timeSlotEnd"));
        vo.setStatus(MapHelper.intg(m, "status"));
        String statusText = MapHelper.str(m, "statusText");
        vo.setStatusText(statusText != null ? statusText : AdminDicts.orderStatusText(vo.getStatus()));
        vo.setVerifiedAt(MapHelper.dt(m, "verifiedAt"));
        // order inner 只给 buyerConfirmed/sellerConfirmed 布尔，无确认时间（遗留）
        vo.setBuyerConfirmAt(null);
        vo.setSellerConfirmAt(null);
        vo.setCancelReason(MapHelper.str(m, "cancelReason"));
        vo.setCreatedAt(MapHelper.dt(m, "createdAt"));
        return vo;
    }
}
