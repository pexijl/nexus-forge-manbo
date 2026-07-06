package com.nexusforge.dto;

/**
 * LogoutRequest DTO，用于封装注销请求的 refreshToken。
 *
 * @param refreshToken
 */
public record LogoutRequest(String refreshToken) {
}