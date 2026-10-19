package com.ershou.cloud.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.cloud.common.exception.BizException;
import com.ershou.cloud.common.result.PageResult;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.result.ResultCode;
import com.ershou.cloud.order.config.OrderProperties;
import com.ershou.cloud.order.dto.CreateOrderDTO;
import com.ershou.cloud.order.dto.OrderVO;
import com.ershou.cloud.order.entity.MeetupOrder;
import com.ershou.cloud.order.event.OrderCreatedEvent;
import com.ershou.cloud.order.feign.InnerItemVO;
import com.ershou.cloud.order.feign.ItemClient;
import com.ershou.cloud.order.mapper.MeetupOrderMapper;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面交单服务（第 5 轮云切片：创建 + 我的列表）。
 *
 * <h3>创建订单的事务边界（演示核心）</h3>
 * <pre>
 * @GlobalTransactional（Seata TM，全局事务 create-meetup-order）
 *   ├─ Feign GET  /inner/items/{id}        查商品（校验 status=2 / stock>=qty，取 sellerId/title/cover/price）
 *   ├─ Feign PUT  /inner/items/{id}/lock   条件扣库存（item-service 的本地事务 → 注册为 AT 分支，写 ershou_item.undo_log）
 *   ├─ INSERT ershou_order.meetup_order    本服务的本地事务（写 ershou_order.undo_log）
 *   └─ debugFail=true → 抛 BizException    → TC 回滚全局事务：item 库存回滚、订单不落库
 * </pre>
 * 方法同时标 {@code @Transactional}：本地事务让"插入 + 事务提交后发 MQ"有明确的提交点
 * （{@link OrderEventPublisher} 用 {@code AFTER_COMMIT} 监听），也让 AT 分支有完整的本地事务上下文。
 * 回滚演示时异常在 publishEvent 之前抛出，所以既不会落库也不会发 MQ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetupOrderService {

    /** 面交单状态：2 待面交（云切片创建即 2，平台/演示单直接进待面交） */
    public static final int STATUS_WAITING = 2;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MeetupOrderMapper orderMapper;
    private final ItemClient itemClient;
    private final ApplicationEventPublisher eventPublisher;
    /** 业务参数（Nacos 动态配置：ershou.order.max-qty / default-status） */
    private final OrderProperties orderProperties;

    /**
     * 创建面交单（POST /api/v1/meetup-orders）。
     *
     * @param buyerId 网关注入的当前用户 id（X-User-Id）
     * @param dto     itemId/quantity/remark/debugFail
     */
    @GlobalTransactional(name = "create-meetup-order")
    @Transactional(rollbackFor = Exception.class)
    public OrderVO create(Long buyerId, CreateOrderDTO dto) {
        Long itemId = dto.getItemId();
        int quantity = dto.getQuantity() == null || dto.getQuantity() < 1 ? 1 : dto.getQuantity();
        // 数量上限来自 Nacos 配置（ershou.order.max-qty，@RefreshScope 动态生效，设计 4.6 演示点）
        if (quantity > orderProperties.getMaxQty()) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "单次最多购买 " + orderProperties.getMaxQty() + " 件");
        }

        // 1) 查商品并校验（Feign；item-service 不可用时走 Sentinel fallback → code=90001）
        InnerItemVO item = loadItem(itemId);
        if (item.getStatus() == null || item.getStatus() != 2) {
            throw new BizException(ResultCode.ITEM_STATUS_ERROR, "商品当前不可购买");
        }
        int stock = item.getStock() == null ? 0 : item.getStock();
        if (stock < quantity) {
            throw new BizException(ResultCode.PARAM_ERROR, "库存不足（剩余 " + stock + " 件）");
        }
        if (Objects.equals(item.getSellerId(), buyerId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能购买自己发布的商品");
        }

        // 2) 条件扣库存（Feign → item-service 本地事务 → Seata AT 分支）
        lockStock(itemId, quantity);

        // 3) 插面交单（本服务本地事务 → Seata AT 分支）
        MeetupOrder order = new MeetupOrder();
        order.setOrderNo(genOrderNo());
        order.setBuyerId(buyerId);
        order.setSellerId(item.getSellerId());
        order.setItemId(itemId);
        order.setItemTitle(item.getTitle());
        order.setItemCover(item.getCoverUrl());
        order.setQuantity(quantity);
        order.setDealPrice(item.getPrice() == null ? BigDecimal.ZERO : item.getPrice());
        // 初始状态也走 Nacos 配置（ershou.order.default-status，默认 2=待面交）
        order.setStatus(orderProperties.getDefaultStatus());
        order.setSellerConfirmAt(LocalDateTime.now());
        order.setRescheduleCount(0);
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);
        log.info("[order][create][buyer={}] orderNo={} item={} qty={}",
                buyerId, order.getOrderNo(), itemId, quantity);

        // 4) Seata 演示：扣库存 + 插单之后主动失败 → 全局回滚（item 库存由 undo_log 还原、订单不落库）
        if (Boolean.TRUE.equals(dto.getDebugFail())) {
            log.warn("[order][create][debugFail] 演示用失败：抛异常触发 Seata 全局回滚 orderNo={}", order.getOrderNo());
            throw new BizException(ResultCode.SYSTEM_ERROR, "演示用失败：debugFail=true 触发 Seata 全局回滚");
        }

        // 5) 事务提交后发 MQ（OrderEventPublisher 的 @TransactionalEventListener(AFTER_COMMIT)）
        eventPublisher.publishEvent(new OrderCreatedEvent(
                order.getId(), order.getOrderNo(), itemId, buyerId, quantity, FMT.format(LocalDateTime.now())));

        // 回查一次把 DB 默认值（created_at/updated_at 由 MySQL 默认值生成）带进响应，
        // 否则 create 返回的 createdAt 是 null（前端/QA 看起来像字段丢了）
        MeetupOrder saved = orderMapper.selectById(order.getId());
        return toVO(saved == null ? order : saved);
    }

    /** 我的面交单分页（GET /api/v1/meetup-orders/mine?status=），标题/封面用 Feign 实时补全 */
    public PageResult<OrderVO> mine(Long buyerId, Integer status, long pageNum, long pageSize) {
        Page<MeetupOrder> page = orderMapper.selectPage(new Page<>(pageNum, Math.min(pageSize, 100)),
                Wrappers.<MeetupOrder>lambdaQuery()
                        .eq(MeetupOrder::getBuyerId, buyerId)
                        .eq(status != null, MeetupOrder::getStatus, status)
                        .orderByDesc(MeetupOrder::getId));
        List<OrderVO> records = page.getRecords().stream()
                .map(o -> toVO(enrichWithItem(o)))
                .toList();
        return PageResult.of(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 内部调试接口用：按 id 查单（不做归属校验，仅 /inner/** 暴露，不经网关） */
    public OrderVO detail(Long orderId) {
        MeetupOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.ORDER_NOT_FOUND);
        }
        return toVO(order);
    }

    // ---------------------------------------------------------------- Feign

    /** Feign 查商品：把 fallback 的 code=90001 / 商品不存在 30001 转成业务异常 */
    private InnerItemVO loadItem(Long itemId) {
        Result<InnerItemVO> result;
        try {
            result = itemClient.get(itemId);
        } catch (Exception e) {
            log.error("[order][feign][get][item={}] 调 item-service 失败: {}", itemId, e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode()) {
            if (result != null && result.getCode() == ResultCode.ITEM_NOT_FOUND.getCode()) {
                throw new BizException(ResultCode.ITEM_NOT_FOUND);
            }
            String msg = result == null || result.getMessage() == null ? "商品服务暂不可用" : result.getMessage();
            throw new BizException(ResultCode.SYSTEM_ERROR, msg);
        }
        if (result.getData() == null) {
            throw new BizException(ResultCode.ITEM_NOT_FOUND);
        }
        return result.getData();
    }

    /**
     * Feign 条件扣库存：item-service 返回 data=1 成功 / 0 失败（不抛异常）。
     * 0 视为"被别人抢先/库存不足"，抛 30004；服务不可用（fallback 90001）抛 90001 文案。
     */
    private void lockStock(Long itemId, int quantity) {
        Result<Integer> lockResult;
        try {
            lockResult = itemClient.lock(itemId, quantity);
        } catch (Exception e) {
            log.error("[order][feign][lock][item={}] 调 item-service 失败: {}", itemId, e.getMessage());
            throw new BizException(ResultCode.SYSTEM_ERROR, "商品服务暂不可用");
        }
        if (lockResult == null || lockResult.getCode() != ResultCode.SUCCESS.getCode()) {
            String msg = lockResult == null || lockResult.getMessage() == null
                    ? "商品服务暂不可用" : lockResult.getMessage();
            throw new BizException(ResultCode.SYSTEM_ERROR, msg);
        }
        if (lockResult.getData() == null || lockResult.getData() != 1) {
            throw new BizException(ResultCode.ITEM_LOCKED, "商品刚被其他同学抢走，看看同款？");
        }
    }

    /**
     * 用 Feign 实时数据补全订单里的商品标题/封面（演示服务间数据拼装）。
     * item-service 不可用时保留订单里的快照字段，不影响列表展示。
     */
    private MeetupOrder enrichWithItem(MeetupOrder order) {
        try {
            Result<InnerItemVO> result = itemClient.get(order.getItemId());
            if (result != null && result.getCode() == ResultCode.SUCCESS.getCode() && result.getData() != null) {
                InnerItemVO item = result.getData();
                if (item.getTitle() != null) {
                    order.setItemTitle(item.getTitle());
                }
                if (item.getCoverUrl() != null) {
                    order.setItemCover(item.getCoverUrl());
                }
            }
        } catch (Exception e) {
            // 降级：Feign 不可用时用下单快照，列表不能因为商品服务抖动就 500
            log.warn("[order][mine][item={}] Feign 补全商品失败，使用订单快照: {}", order.getItemId(), e.getMessage());
        }
        return order;
    }

    // ---------------------------------------------------------------- 工具

    private String genOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private OrderVO toVO(MeetupOrder o) {
        OrderVO vo = new OrderVO();
        vo.setId(o.getId());
        vo.setOrderId(o.getId());
        vo.setOrderNo(o.getOrderNo());
        vo.setItemId(o.getItemId());
        vo.setItemTitle(o.getItemTitle());
        vo.setItemCover(o.getItemCover());
        vo.setBuyerId(o.getBuyerId());
        vo.setSellerId(o.getSellerId());
        vo.setQuantity(o.getQuantity());
        vo.setDealPrice(o.getDealPrice());
        vo.setStatus(o.getStatus());
        vo.setStatusText(statusText(o.getStatus()));
        vo.setPointId(o.getPointId());
        vo.setRemark(o.getRemark());
        vo.setCreatedAt(o.getCreatedAt());
        return vo;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "待卖家确认";
            case 2 -> "待面交";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已失效";
            case 6 -> "爽约";
            case 7 -> "纠纷中";
            default -> "未知";
        };
    }
}
