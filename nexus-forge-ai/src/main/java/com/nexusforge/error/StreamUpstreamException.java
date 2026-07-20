package com.nexusforge.error;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;

public class StreamUpstreamException extends LlmException {
    public StreamUpstreamException(String detail) {
        super(ResultCode.LLM_PROVIDER_ERROR, "上游流错误: " + detail);
    }
}