package com.ershou.cloud.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;
import java.util.function.Function;

/**
 * 分页响应（与单体 {@code PageResult} 结构一致：{@code {total,pageNum,pageSize,records}}）。
 *
 * <p>提供 IPage 工厂：common 对 mybatis-plus-core 是 optional 依赖，
 * 没有 MP 的模块不会加载到该方法（class 加载是惰性的）。
 */
@Data
public class PageResult<T> {
    private long total;
    private long pageNum;
    private long pageSize;
    private List<T> records;

    public static <E, T> PageResult<T> of(IPage<E> page, Function<E, T> mapper) {
        PageResult<T> r = new PageResult<>();
        r.total = page.getTotal();
        r.pageNum = page.getCurrent();
        r.pageSize = page.getSize();
        r.records = page.getRecords().stream().map(mapper).toList();
        return r;
    }

    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        PageResult<T> r = new PageResult<>();
        r.total = total;
        r.pageNum = pageNum;
        r.pageSize = pageSize;
        r.records = records;
        return r;
    }

    /** 空页（列表接口的兜底返回） */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return of(List.of(), 0, pageNum, pageSize);
    }
}
