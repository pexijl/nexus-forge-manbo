package com.nexusforge.idempotent;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.BusinessException;

public class IdempotentException extends BusinessException {
    public IdempotentException(String message) {
        super(ResultCode.IDEMPOTENT_CONFLICT, message);
    }
}
