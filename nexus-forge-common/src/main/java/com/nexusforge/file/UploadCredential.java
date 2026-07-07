package com.nexusforge.file;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * 上传凭证
 */
@Data
@Builder
public class UploadCredential {
    /**
     * PUT 目标 URL
     */
    private String uploadUrl;
    /**
     * 最终对外可访问的 URL
     */
    private String publicUrl;
    /**
     * 客户端必须在 PUT 时带的请求头
     */
    private Map<String, String> headers;
    /**
     * 过期时间
     */
    private Instant expiresAt;
}
