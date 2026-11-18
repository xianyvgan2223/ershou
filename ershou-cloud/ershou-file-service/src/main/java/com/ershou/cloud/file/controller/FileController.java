package com.ershou.cloud.file.controller;

import com.ershou.cloud.common.result.Result;
import com.ershou.cloud.common.web.UserContext;
import com.ershou.cloud.file.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文件接口 —— 与旧单体 {@code module/file/controller/FileController} 完全对齐：
 *
 * <ul>
 *   <li>路径：{@code POST /api/v1/files/upload}（multipart 字段 {@code file}，query {@code biz} 默认 item）；</li>
 *   <li>鉴权：旧单体 {@code StpUtil.checkLogin()} → 微服务改为网关注入 {@code X-User-Id}，
 *       这里用 {@code UserContext.requireUserId()} 保持"必须登录"语义（未登录返回 10002）；</li>
 *   <li>返回：{@code Result<Map<String,String>>}，data = {@code {"url": "<公开可访问 URL>"}}，
 *       前端/App 的 {@code data.url} 解析不用改。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService fileStorageService;

    @PostMapping("/upload")
    public Result<Map<String, String>> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(defaultValue = "item") String biz) {
        UserContext.requireUserId();
        String url = fileStorageService.upload(file, biz);
        return Result.ok(Map.of("url", url));
    }
}
