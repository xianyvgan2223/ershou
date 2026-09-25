package com.ershou.campus.common.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ershou.storage")
public class StorageProperties {
    /** minio / local */
    private String mode = "minio";
    private String endpoint = "http://127.0.0.1:9010";
    private String accessKey = "ershou";
    private String secretKey = "";
    private String bucket = "ershou";
    private String publicBaseUrl = "http://127.0.0.1:9010/ershou";
    private int maxSizeMb = 5;
    private int imageMaxEdge = 1280;
    private String localPath = "./upload/";
    private String localUrlPrefix = "http://127.0.0.1:8080/static/";
}
