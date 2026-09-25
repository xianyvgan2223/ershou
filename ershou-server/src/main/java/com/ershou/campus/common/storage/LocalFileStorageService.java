package com.ershou.campus.common.storage;

import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 降级实现：没有 MinIO 时把图片写到本地磁盘，通过 /static/** 访问 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ershou.storage.mode", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private final StorageProperties props;

    @Override
    public String upload(MultipartFile file, String biz) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择要上传的图片");
        }
        try {
            String dir = biz == null || biz.isBlank() ? "common" : biz.replaceAll("[^a-zA-Z]", "");
            String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            String rel = dir + "/" + date + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
            File target = new File(props.getLocalPath(), rel);
            File parent = target.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IllegalStateException("创建目录失败: " + parent);
            }
            file.transferTo(target);
            return props.getLocalUrlPrefix() + rel;
        } catch (Exception e) {
            log.error("本地保存失败", e);
            throw new BizException(ResultCode.STORAGE_ERROR);
        }
    }
}
