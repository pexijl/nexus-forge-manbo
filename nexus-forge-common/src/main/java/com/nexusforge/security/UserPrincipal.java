package com.nexusforge.security;

/**
 * 用户身份信息类，封装了用户的唯一标识和用户名，用于在系统中表示已认证的用户
 * @param userId 用户Id
 * @param username 用户名
 */
public record UserPrincipal(Long userId, String username) {
}
