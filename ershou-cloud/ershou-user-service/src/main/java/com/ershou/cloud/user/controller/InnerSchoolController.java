package com.ershou.cloud.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.user.entity.Campus;
import com.ershou.cloud.user.entity.School;
import com.ershou.cloud.user.mapper.CampusMapper;
import com.ershou.cloud.user.mapper.SchoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学校 / 校区内部接口（`/inner/schools`、`/inner/campuses`，契约第三节）。
 *
 * <p>用途：map-service 渲染校区底图配置、item-service 发布页校验学校校区、
 * platform-service 代卖点归属校验等。对外公开读接口在 {@link SchoolController}。
 *
 * <p>与公开接口的差异：内部接口**不过滤 status**（admin 需要看到停用学校/校区），
 * 返回实体全字段（campus 含 mapType/mapConfig，map-service 要用）。
 */
@RestController
@RequestMapping("/inner")
@RequiredArgsConstructor
public class InnerSchoolController {

    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;

    /** 学校列表（全量，按 id 升序） */
    @GetMapping("/schools")
    public Result<List<School>> schools() {
        return Result.ok(schoolMapper.selectList(Wrappers.<School>lambdaQuery()
                .orderByAsc(School::getId)));
    }

    /** 校区列表：schoolId 可选（不传 = 全部校区，按 id 升序） */
    @GetMapping("/campuses")
    public Result<List<Campus>> campuses(@RequestParam(required = false) Long schoolId) {
        LambdaQueryWrapper<Campus> w = Wrappers.<Campus>lambdaQuery();
        if (schoolId != null) {
            w.eq(Campus::getSchoolId, schoolId);
        }
        w.orderByAsc(Campus::getId);
        return Result.ok(campusMapper.selectList(w));
    }
}
