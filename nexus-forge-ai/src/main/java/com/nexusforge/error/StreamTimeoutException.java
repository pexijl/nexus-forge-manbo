package com.nexusforge.error;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;

public class StreamTimeoutException extends LlmException {
    public StreamTimeoutException(String detail) {
        super(ResultCode.LLM_UPSTREAM_TIMEOUT, "流超时: " + detail);
    }
}