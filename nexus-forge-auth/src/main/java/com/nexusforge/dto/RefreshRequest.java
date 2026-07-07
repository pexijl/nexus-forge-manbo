package com.nexusforge.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * RefreshRequest DTO，用于封装刷新请求的 refreshToken。
 *
 * @param refreshToken
 */
public record RefreshRequest(@NotBlank String refreshToken) {
}
