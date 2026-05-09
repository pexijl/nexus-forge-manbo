package com.nexusforge.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),
    OT_FOUND(404, "资源不存在"),
    UNAUTHORIZED(401, "未授权访问");

    private final Integer code;
    private final String message;
}
