package com.ershou.cloud.user.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.user.entity.Campus;
import com.ershou.cloud.user.entity.School;
import com.ershou.cloud.user.mapper.CampusMapper;
import com.ershou.cloud.user.mapper.SchoolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学校 / 校区（公开读接口，与单体 `module/school/controller/SchoolController` 一致）。
 *
 * <p>返回的是实体本身（含 mapType/mapConfig 等字段），与单体同结构 —— 前端
 * `api/school.ts` 的 `School`/`Campus` 类型只取其中几个字段，多返回不影响。
 */
@RestController
@RequestMapping("/api/v1/schools")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;

    @GetMapping
    public Result<List<School>> list() {
        return Result.ok(schoolMapper.selectList(Wrappers.<School>lambdaQuery().eq(School::getStatus, 1)));
    }

    @GetMapping("/{schoolId}/campuses")
    public Result<List<Campus>> campuses(@PathVariable Long schoolId) {
        return Result.ok(campusMapper.selectList(Wrappers.<Campus>lambdaQuery()
                .eq(Campus::getSchoolId, schoolId).eq(Campus::getStatus, 1)));
    }
}
