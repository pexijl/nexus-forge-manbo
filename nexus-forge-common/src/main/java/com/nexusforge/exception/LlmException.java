package com.nexusforge.exception;

import com.nexusforge.enums.ResultCode;

/**
 * LLM 网关业务异常。复用 BusinessException 的 code/httpStatus 字段;
 * LLM_* 错误码映射见 GlobalExceptionHandler.mapStatus。
 */
public class LlmException extends BusinessException {
    public LlmException(ResultCode code) {
        super(code);
    }
    public LlmException(ResultCode code, String detail) {
        super(code, detail);
    }
}