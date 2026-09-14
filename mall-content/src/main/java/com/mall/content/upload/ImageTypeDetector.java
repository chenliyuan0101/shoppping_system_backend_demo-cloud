package com.mall.content.upload;

import java.io.IOException;
import java.io.InputStream;

/**
 * 图片"真实类型"识别（魔数嗅探）。
 *
 * <p>为什么不能只信 {@code MultipartFile#getContentType}：那是**客户端自己填的字符串**，
 * 改个请求头就能把任意字节（html/js/svg/压缩包）声明成 {@code image/png} 存进公开桶。
 * 所以落库前用文件头判断真实类型，并让对象后缀由**检测结果**决定，而不是沿用原始文件名。
 *
 * <p>覆盖项目允许的 4 种图片格式：JPEG / PNG / GIF / WebP。识别不出即拒绝。
 */
public final class ImageTypeDetector {

    public enum ImageType {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        GIF("gif", "image/gif"),
        WEBP("webp", "image/webp");

        private final String extension;
        private final String contentType;

        ImageType(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public String contentType() {
            return contentType;
        }
    }

    private ImageTypeDetector() {
    }

    /**
     * 读取文件头判断真实图片类型。
     *
     * @param in 输入流（内部只读前 12 字节，不关闭流）
     * @return 识别到的类型；无法识别返回 null
     */
    public static ImageType detect(InputStream in) throws IOException {
        byte[] head = new byte[12];
        int read = 0;
        while (read < head.length) {
            int n = in.read(head, read, head.length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        if (read < 4) {
            return null;
        }
        return detect(head, read);
    }

    static ImageType detect(byte[] h, int len) {
        // JPEG: FF D8 FF
        if (len >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (len >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && (h[4] & 0xFF) == 0x0D && (h[5] & 0xFF) == 0x0A && (h[6] & 0xFF) == 0x1A && (h[7] & 0xFF) == 0x0A) {
            return ImageType.PNG;
        }
        // GIF: "GIF87a" / "GIF89a"
        if (len >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8'
                && (h[4] == '7' || h[4] == '9') && h[5] == 'a') {
            return ImageType.GIF;
        }
        // WebP: "RIFF" .... "WEBP"
        if (len >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return ImageType.WEBP;
        }
        return null;
    }
}
