package com.nexusforge.exception;

import com.nexusforge.enums.ResultCode;

/**
 * 业务异常类，表示业务逻辑错误，例如用户不存在、权限不足等
 */
public class AuthException extends BaseException {
    public AuthException(ResultCode resultCode) {
        super(resultCode);
    }

    public AuthException(ResultCode resultCode, String detail) {
        super(resultCode, detail);
    }

    public AuthException(Integer code, String message) {
        super(code, message);
    }
}
