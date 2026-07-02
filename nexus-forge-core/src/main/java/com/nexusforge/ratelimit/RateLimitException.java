package com.nexusforge.ratelimit;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;

public class RateLimitException extends BusinessException {
    public RateLimitException(String message) {
        super(ResultCode.RATE_LIMITED, message);
    }
}
