package com.nexusforge.exception;

import com.nexusforge.enums.ResultCode;
import lombok.Getter;

/**
 * 基础异常类，所有自定义异常都应该继承此类
 */
@Getter
public class BaseException extends RuntimeException{

    private final Integer code;

    public BaseException(ResultCode resultCode){
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
    }

    public BaseException(ResultCode resultCode, String detail) {
        super(resultCode.getMessage() + ": " + detail);
        this.code = resultCode.getCode();
    }

    public BaseException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
