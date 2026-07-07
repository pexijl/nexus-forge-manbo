package com.nexusforge.dto;

import com.nexusforge.util.JwtUtil.TokenPair;

/** 登录/刷新接口返回的 access + refresh 包 */
public record TokenBundle(
        TokenPair access,
        TokenPair refresh
) {}
