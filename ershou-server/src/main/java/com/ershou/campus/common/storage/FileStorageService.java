package com.ershou.campus.common.storage;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {
    /**
     * 上传图片并返回可公开访问的 URL
     *
     * @param file 图片文件
     * @param biz  业务目录：item / avatar / chat / consign / report
     */
    String upload(MultipartFile file, String biz);
}
