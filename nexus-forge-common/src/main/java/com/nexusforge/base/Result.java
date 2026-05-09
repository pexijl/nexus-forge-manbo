package com.nexusforge.base;

import com.nexusforge.constant.ResultCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private Integer code;
    private String message;
    private T data;

    /**
     * 成功响应
     */
    public static <T> Result<T> success() {
        return Result.<T>builder().code(ResultCode.SUCCESS.getCode()).message("success").build();
    }


    /**
     *  成功响应
     * @param data 返回数据
     */
    public static <T> Result<T> success(T data) {
        return Result.<T>builder().code(ResultCode.SUCCESS.getCode()).message("success").data(data).build();
    }

    /**
     * 成功响应
     * @param message 提示信息
     * @param data 返回数据
     */
    public static <T> Result<T> success(String message, T data) {
        // TODO: 200 -> constant
        return Result.<T>builder().code(ResultCode.SUCCESS.getCode()).message(message).data(data).build();
    }

    /**
     * 失败响应
     * @param message 错误信息
     */
    public static <T> Result<T> fail(String message) {
        // TODO: 500 -> constant
        return Result.<T>builder().code(ResultCode.FAIL.getCode()).message(message).build();
    }

    /**
     * 失败响应
     * @param code 错误码
     * @param message 错误信息
     */
    public static <T> Result<T> fail(Integer code, String message) {
        return Result.<T>builder().code(code).message(message).build();
    }
}
