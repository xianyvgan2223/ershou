package com.ershou.cloud.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 存储配置（前缀 {@code ershou.storage}，与旧单体 {@code com.ershou.campus.common.storage.StorageProperties} 同构）。
 *
 * <p>全部来自 Nacos dataId {@code ershou-file-service.yaml}，本地 application.yml 不写死凭据。
 * 演示环境只有 minio 模式；保留 {@code mode} 字段便于与旧配置对齐/后续扩展 local 模式。
 */
@Data
@ConfigurationProperties(prefix = "ershou.storage")
public class StorageProperties {
    /** minio / local（本服务当前只实现 minio） */
    private String mode = "minio";
    private String endpoint = "http://127.0.0.1:9010";
    private String accessKey = "ershou";
    private String secretKey = "";
    private String bucket = "ershou";
    private String publicBaseUrl = "http://127.0.0.1:9010/ershou";
    /** 单文件大小上限（MB），旧单体默认 5 */
    private int maxSizeMb = 5;
    /** 压缩后长边上限（px），旧单体默认 1280 */
    private int imageMaxEdge = 1280;
}
