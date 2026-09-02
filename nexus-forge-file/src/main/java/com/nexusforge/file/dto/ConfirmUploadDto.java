package com.nexusforge.file.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 前端直传完成后的 confirm 请求体。
 *
 * @param etag 对象存储返回的 ETag(可空;空时仅翻状态不写 etag)
 * @param size 实际字节数(可空但建议传;用于 size 校正)
 * @since P2 commit 3
 */
public record ConfirmUploadDto(
        @Size(max = 128) String etag,
        @Positive Long size
) { }
