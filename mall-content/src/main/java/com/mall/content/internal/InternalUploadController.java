package com.mall.content.internal;

import com.mall.content.support.ApiResponse;
import com.mall.content.support.BusinessException;
import com.mall.content.upload.FileStorageService;
import com.mall.content.upload.ImageTypeDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 文件上传的内部接口：**唯一决定"哪些字节允许进公开桶"的地方**。
 *
 * <p>P2 决策 1 之后，面向会员/管理员的 {@code /api/upload}、{@code /api/admin/upload} 仍在单体
 * （那里才有登录态），单体校验完大小就把字节转发到这里；本服务负责
 * <b>按文件内容（魔数）识别真实类型</b>并写入 MinIO。
 *
 * <p>为什么魔数校验放在本服务而不是调用方：它是"落库"这一步的前置条件，
 * 与存储实现绑在一起——把判断留在数据属主这里，才不会有第二个"忘了校验"的调用方。
 * 文案与改造前逐字一致（{@code 400}），因为它是客户端可见的对外契约（C1）。
 */
@RestController
@RequestMapping("/internal/v1/content")
@RequiredArgsConstructor
public class InternalUploadController {

    private final FileStorageService fileStorageService;

    @Value("${mall.upload.image-max-mb:5}")
    private long maxMb;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(toMap(validateAndStore(file)));
    }

    /**
     * 校验顺序：大小 → **真实类型（魔数）** → 落库。
     * 不使用客户端声明的 {@code Content-Type}，也不使用原文件名推后缀
     * （否则把 html/js/svg 改名成 .png 就能存进公开桶，等于把对象存储当免费托管）。
     */
    private FileStorageService.UploadResult validateAndStore(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择文件");
        }
        if (file.getSize() > maxMb * 1024 * 1024) {
            throw new BusinessException(400, "图片不能超过 " + maxMb + "MB");
        }
        ImageTypeDetector.ImageType type;
        try (InputStream in = file.getInputStream()) {
            type = ImageTypeDetector.detect(in);
        } catch (IOException e) {
            throw new BusinessException(400, "文件读取失败");
        }
        if (type == null) {
            throw new BusinessException(400, "仅支持 jpg/png/webp/gif 图片（按文件内容识别，请勿修改后缀）");
        }
        return fileStorageService.upload(file, type);
    }

    private static Map<String, Object> toMap(FileStorageService.UploadResult r) {
        return Map.of("url", r.url(), "name", r.name(), "size", r.size(), "contentType", r.contentType());
    }
}
