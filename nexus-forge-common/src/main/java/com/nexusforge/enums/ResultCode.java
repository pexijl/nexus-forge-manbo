package com.nexusforge.enums;

import lombok.Getter;

/**
 * 统一的结果码枚举类，包含成功、失败、客户端错误和业务错误等常见的结果码
 */
@Getter
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),
    INTERNAL_ERROR(500, "服务器内部错误"),

    //  客户端错误
    BAD_REQUEST(400, "请求参数错误"),
    VALIDATION_FAILED(1001, "参数校验失败"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    INVALID_CREDENTIALS(1003, "账号或密码错误"),
    INVALID_PARAMS(1004, "无效的参数"),
    FORBIDDEN(1005, "无访问权限"),
    TOKEN_REFRESH_FAILED(1006, "刷新 Token 失败"),
    TOKEN_BLACKLISTED(1007, "Token 已失效，请重新登录"),
    INVALID_TOKEN_TYPE(1010, "Token 类型错误，请使用 refresh token"),
    TOKEN_REVOKED(1011, "Token 已被吊销，请重新登录"),
    TOKEN_VERSION_MISMATCH(1012, "Token 版本不一致，请重新登录"),

    // 业务错误
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_ALREADY_EXISTS(2002, "用户已存在"),
    EMAIL_ALREADY_EXISTS(2003, "邮箱已存在"),
    REGISTRATION_FAILED(2004, "注册失败"),
    FILE_UPLOAD_FAILED(2005, "文件上传失败"),
    FILE_TOO_LARGE(2006, "文件大小超过限制"),
    FILE_BIZ_TYPE_IS_EMPTY(2007, "文件业务类型为空"),
    IDEMPOTENT_CONFLICT(2008, "幂等请求冲突"),
    RATE_LIMITED(2009, "请求过于频繁，请稍后再试"),
    AVATAR_UPLOAD_FAILED(2010, "头像上传失败"),
    OLD_PASSWORD_INCORRECT(2011, "旧密码不正确"),
    NEW_PASSWORD_SAME_AS_OLD(2012, "新密码不能与旧密码相同"),

    // AI 网关
    LLM_CONFIG_MISSING(3001, "LLM 配置缺失"),
    LLM_MODEL_NOT_FOUND(3002, "模型不存在或未启用"),
    LLM_INVALID_REQUEST(3003, "LLM 请求参数无效"),
    LLM_PROVIDER_ERROR(3004, "LLM 服务商返回错误"),
    LLM_UPSTREAM_TIMEOUT(3005, "LLM 上游响应超时"),
    LLM_RATE_LIMITED(3006, "LLM 请求被速率限制"),
    LLM_QUOTA_EXCEEDED(3007, "LLM 配额已用尽"),
    LLM_ALL_VENDORS_FAILED(3008, "所有降级链均失败"),
    LLM_CIRCUIT_OPEN(3009, "降级链已熔断,暂不可用");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 通过 Integer code 反查枚举项,找不到返回 {@link #INTERNAL_ERROR}。
     * 用于 {@link com.nexusforge.exception.BaseException#getCode()} 拿到的 Integer 后做枚举映射。
     */
    public static ResultCode fromCodeValue(Integer code) {
        if (code == null) return INTERNAL_ERROR;
        for (ResultCode c : values()) {
            if (c.code.equals(code)) return c;
        }
        return INTERNAL_ERROR;
    }
}
