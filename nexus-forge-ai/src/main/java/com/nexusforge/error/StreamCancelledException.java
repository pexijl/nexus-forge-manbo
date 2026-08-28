package com.nexusforge.error;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;

public class StreamCancelledException extends LlmException {
    public StreamCancelledException(String detail) {
        super(ResultCode.LLM_INVALID_REQUEST, "客户端取消: " + detail);
    }
}