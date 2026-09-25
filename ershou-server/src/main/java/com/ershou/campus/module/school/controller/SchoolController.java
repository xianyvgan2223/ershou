package com.ershou.campus.module.school.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ershou.campus.common.result.Result;
import com.ershou.campus.module.school.entity.Campus;
import com.ershou.campus.module.school.entity.School;
import com.ershou.campus.module.school.mapper.CampusMapper;
import com.ershou.campus.module.school.mapper.SchoolMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "学校")
@RestController
@RequestMapping("/api/v1/schools")
@RequiredArgsConstructor
public class SchoolController {

    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;

    @Operation(summary = "学校列表（公开）")
    @GetMapping
    public Result<List<School>> list() {
        return Result.ok(schoolMapper.selectList(Wrappers.<School>lambdaQuery().eq(School::getStatus, 1)));
    }

    @Operation(summary = "校区列表（公开）")
    @GetMapping("/{schoolId}/campuses")
    public Result<List<Campus>> campuses(@PathVariable Long schoolId) {
        return Result.ok(campusMapper.selectList(Wrappers.<Campus>lambdaQuery()
                .eq(Campus::getSchoolId, schoolId).eq(Campus::getStatus, 1)));
    }
}
