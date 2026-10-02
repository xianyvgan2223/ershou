package com.ershou.campus.module.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ershou.campus.common.result.PageResult;
import com.ershou.campus.module.admin.vo.AdminOrderVO;
import com.ershou.campus.module.order.entity.MeetupOrder;
import com.ershou.campus.module.order.mapper.MeetupOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 面交单查询（成交明细 / 异常订单排查） */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MeetupOrderMapper orderMapper;
    private final AdminViewAssembler assembler;

    public PageResult<AdminOrderVO> page(Integer status, Long buyerId, Long sellerId, String keyword,
                                         long pageNum, long pageSize) {
        LambdaQueryWrapper<MeetupOrder> w = Wrappers.<MeetupOrder>lambdaQuery();
        if (status != null) {
            w.eq(MeetupOrder::getStatus, status);
        }
        if (buyerId != null) {
            w.eq(MeetupOrder::getBuyerId, buyerId);
        }
        if (sellerId != null) {
            w.eq(MeetupOrder::getSellerId, sellerId);
        }
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            // 订单号或商品标题命中即可；用 and(...) 包一层括号，避免 OR 泄漏到其它条件的 AND 上
            w.and(q -> q.like(MeetupOrder::getOrderNo, kw).or().like(MeetupOrder::getItemTitle, kw));
        }
        w.orderByDesc(MeetupOrder::getId);
        Page<MeetupOrder> page = orderMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return assembler.orderPage(page);
    }
}
