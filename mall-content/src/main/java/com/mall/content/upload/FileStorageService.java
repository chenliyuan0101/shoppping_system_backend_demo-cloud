package com.mall.content.upload;

import com.mall.content.support.BusinessException;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务：写入 MinIO(桶自动创建)，返回公网访问 URL。
 * 依赖 mall.minio.* 配置；MinIO 未启动时抛业务错误提示启动。
 */
@Slf4j
@Service
public class FileStorageService {

    private final MinioClient minioClient;
    private final String endpoint;
    private final String bucket;
    private final Object lock = new Object();
    private volatile boolean bucketReady = false;

    public FileStorageService(MinioClient minioClient,
                              com.mall.content.support.MinioProperties props) {
        this.minioClient = minioClient;
        this.endpoint = trimSlash(props.endpoint());
        this.bucket = props.bucket() == null ? "mall" : props.bucket();
    }

    /**
     * 上传图片，返回 {url,name,size,contentType}。
     *
     * @param type 由 {@link ImageTypeDetector} 按**文件内容**识别出的真实类型：
     *             对象后缀与写入的 Content-Type 都以它为准，不再采信客户端声明值
     */
    public UploadResult upload(MultipartFile file, ImageTypeDetector.ImageType type) {
        ensureBucket();
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String objectName = datePath + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + type.extension();
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(in, file.getSize(), -1)
                    .contentType(type.contentType())
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传失败", e);
            throw new BusinessException(500, "上传失败，请确认 MinIO 已启动");
        }
        String url = endpoint + "/" + bucket + "/" + objectName;
        return new UploadResult(url, original, file.getSize(), type.contentType());
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (lock) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                        .bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                // 桶设为公开只读：浏览器才能直接访问图片 URL(仅允许 s3:GetObject)
                String policy = "{"
                        + "\"Version\":\"2012-10-17\","
                        + "\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},"
                        + "\"Action\":[\"s3:GetObject\"],"
                        + "\"Resource\":[\"arn:aws:s3:::" + bucket + "/*\"]}]}";
                minioClient.setBucketPolicy(io.minio.SetBucketPolicyArgs.builder()
                        .bucket(bucket).config(policy).build());
                bucketReady = true;
            } catch (Exception e) {
                throw new BusinessException(500, "连接 MinIO 失败：" + e.getMessage());
            }
        }
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "http://127.0.0.1:9000";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 上传结果 */
    public record UploadResult(String url, String name, long size, String contentType) {
    }
}
