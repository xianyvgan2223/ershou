package com.ershou.campus.common.storage;

import com.ershou.campus.common.exception.BizException;
import com.ershou.campus.common.result.ResultCode;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ershou.storage.mode", havingValue = "minio", matchIfMissing = true)
public class MinioFileStorageService implements FileStorageService {

    private final StorageProperties props;
    private MinioClient client;

    @PostConstruct
    public void init() {
        this.client = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
        try {
            String bucket = props.getBucket();
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                String policy = """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                        "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                        """.formatted(bucket).replace("\n", "");
                client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
                log.info("minio bucket [{}] created", bucket);
            } else {
                log.info("minio bucket [{}] already exists", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO 初始化失败（上传接口将返回 90003）: {}", e.getMessage());
        }
    }

    @Override
    public String upload(MultipartFile file, String biz) {
        validate(file);
        String key = buildKey(file, biz);
        try {
            byte[] data = compress(file);
            try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
                client.putObject(PutObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(key)
                        .stream(in, data.length, 10 * 1024 * 1024)
                        .contentType("image/jpeg")
                        .build());
            }
            String url = props.getPublicBaseUrl() + "/" + key;
            log.info("[file][upload][{}][ok] {}", biz, url);
            return url;
        } catch (Exception e) {
            log.error("上传 MinIO 失败: {}", e.getMessage(), e);
            throw new BizException(ResultCode.STORAGE_ERROR);
        }
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "请选择要上传的图片");
        }
        long max = (long) props.getMaxSizeMb() * 1024 * 1024;
        if (file.getSize() > max) {
            throw new BizException(ResultCode.PARAM_ERROR, "图片不能超过 " + props.getMaxSizeMb() + "MB");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
            throw new BizException(ResultCode.PARAM_ERROR, "仅支持 jpg / png / webp 格式");
        }
    }

    private String buildKey(MultipartFile file, String biz) {
        String dir = (biz == null || biz.isBlank()) ? "common" : biz.replaceAll("[^a-zA-Z]", "");
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
        return dir + "/" + date + "/" + UUID.randomUUID().toString().replace("-", "") + ".jpg";
    }

    /** 统一压缩为 jpg：长边超过配置值才等比缩放（size() 会放大图片，所以要先判断） */
    private byte[] compress(MultipartFile file) throws Exception {
        BufferedImage img = ImageIO.read(file.getInputStream());
        if (img == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "图片无法解析，请换一张");
        }
        int edge = Math.max(img.getWidth(), img.getHeight());
        int max = props.getImageMaxEdge();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thumbnails.Builder<BufferedImage> builder = Thumbnails.of(img)
                .outputQuality(0.8)
                .outputFormat("jpg");
        if (edge > max) {
            builder.size(max, max).keepAspectRatio(true);
        } else {
            builder.scale(1.0);
        }
        builder.toOutputStream(out);
        return out.toByteArray();
    }
}