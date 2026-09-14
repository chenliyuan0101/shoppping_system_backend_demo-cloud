package com.mall.content.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 配置(mall.minio.*，见 application.yaml)。
 */
@ConfigurationProperties(prefix = "mall.minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket) {
}
