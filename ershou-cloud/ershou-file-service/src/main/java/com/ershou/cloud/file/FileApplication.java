package com.ershou.cloud.file;

import com.ershou.cloud.file.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 文件服务（:8089）—— 第 6 轮全量拆分中从旧单体 {@code module/file} 拆出的 MinIO 上传能力。
 *
 * <p>职责边界：
 * <ul>
 *   <li>{@code POST /api/v1/files/upload}（multipart 字段 file，query 参数 biz）：上传图片到 MinIO，
 *       返回与旧单体完全一致的 {@code Result<Map<String,String>>}（data = {@code {"url": "..."}}）；</li>
 *   <li>无数据库、无 Redis、无 Sa-Token：登录态由网关统一校验并注入 {@code X-User-Id}，
 *       服务内用 {@code UserContext.requireUserId()} 要求登录（直接调试时手工带该 header）；</li>
 *   <li>MinIO endpoint/access-key/secret-key/bucket/public-base-url 全部来自 Nacos 配置
 *       dataId {@code ershou-file-service.yaml}（演示配置中心）。</li>
 * </ul>
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableConfigurationProperties(StorageProperties.class)
public class FileApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileApplication.class, args);
    }
}
