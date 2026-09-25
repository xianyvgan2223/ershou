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

/** 面交单查询（成交明细 / 异常订单排查） */
@Service
@RequiredArgsConstructor
public class AdminOrderService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MeetupOrderMapper orderMapper;
    private final AdminViewAssembler assembler;

    public PageResult<AdminOrderVO> page(Integer status, Long buyerId, Long sellerId,
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
        w.orderByDesc(MeetupOrder::getId);
        Page<MeetupOrder> page = orderMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE)), w);
        return assembler.orderPage(page);
    }
}
