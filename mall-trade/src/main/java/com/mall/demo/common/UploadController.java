package com.mall.demo.common;

import com.mall.demo.common.client.ContentInternalClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 图片上传（对外契约不变）。
 * - POST /api/upload         会员登录后可传(评价晒图等)，限流 20 次/分钟
 * - POST /api/admin/upload   管理员登录后可传(商品/轮播/品牌图等)，限流 60 次/分钟
 *
 * <p><b>P2 起"存"这一步在 {@code mall-content}</b>（MinIO 与魔数校验都搬过去了，本类只做转发）：
 * 登录态与限流留在单体（那里才有 {@code @MemberId} 与 {@code RateLimitAspect}），
 * 单体只保留"空文件 / 大小"两个不必过网络的校验，转发后把
 * {@code {url,name,size,contentType}} 原样返回——客户端零感知。
 *
 * <p>⚠️ 曾经的类型校验（按文件内容/魔数识别 jpg/png/webp/gif）**不是被删掉了，而是搬去了属主服务**：
 * 它决定"哪些字节能进公开桶"，必须和存储绑在一起。对应断言见 {@code mall-content} 的
 * {@code InternalUploadMySqlTest}（含"改名成 .png 的 HTML → 400"这条安全用例）。
 */
@Tag(name = "文件上传")
@RestController
@RequiredArgsConstructor
public class UploadController {

    private final ContentInternalClient contentClient;

    @Value("${mall.upload.image-max-mb:5}")
    private long maxMb;

    /**
     * 会员上传图片。
     *
     * <p>{@code @MemberId} 参数由 {@link com.mall.demo.auth.support.MemberIdArgumentResolver} 解析，
     * <b>解析即完成登录校验</b>（未登录/已禁用 → 401），因此这里不需要用到它的值。
     */
    @Operation(summary = "会员上传图片")
    @RateLimit(scope = "upload", limit = 20, windowSeconds = 60, by = RateLimit.By.USER)
    @PostMapping("/api/upload")
    public ApiResponse<Map<String, Object>> uploadMember(
            @MemberId Long memberId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(store(file));
    }

    @Operation(summary = "管理员上传图片")
    @RateLimit(scope = "admin_upload", limit = 60, windowSeconds = 60, by = RateLimit.By.USER)
    @PostMapping("/api/admin/upload")
    public ApiResponse<Map<String, Object>> uploadAdmin(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(store(file));
    }

    /**
     * 前置校验（快速失败，不过网络）+ 转发给内容域。
     *
     * <p>空的/超大文件在这里就挡掉：文案与改造前逐字一致；其余判定（真实类型）在内容域。
     */
    private Map<String, Object> store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "请选择文件");
        }
        if (file.getSize() > maxMb * 1024 * 1024) {
            throw new BusinessException(400, "图片不能超过 " + maxMb + "MB");
        }
        return contentClient.upload(file);
    }
}
