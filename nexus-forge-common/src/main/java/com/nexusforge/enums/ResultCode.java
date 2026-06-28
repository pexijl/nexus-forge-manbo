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

    // 业务错误
    USER_NOT_FOUND(2001, "用户不存在"),
    USER_ALREADY_EXISTS(2002, "用户已存在"),
    EMAIL_ALREADY_EXISTS(2003, "邮箱已存在"),
    REGISTRATION_FAILED(2004, "注册失败"),
    FILE_UPLOAD_FAILED(2005, "文件上传失败"),
    FILE_BIZ_TYPE_IS_EMPTY(2007, "文件业务类型为空"),
    AVATAR_UPLOAD_FAILED(2010, "头像上传失败");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
