package com.nexusforge.exception;

import com.nexusforge.enums.ResultCode;

/**
 * 业务异常类，表示业务逻辑错误，例如用户不存在、权限不足等
 */
public class BusinessException extends BaseException{
    public BusinessException(ResultCode resultCode) {
        super(resultCode);
    }

    public BusinessException(ResultCode resultCode, String detail) {
        super(resultCode, detail);
    }

    public BusinessException(Integer code, String message) {
        super(code, message);
    }
}
