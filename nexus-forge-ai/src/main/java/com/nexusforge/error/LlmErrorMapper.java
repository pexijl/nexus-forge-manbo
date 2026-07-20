package com.nexusforge.error;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;

import java.net.ConnectException;
import java.time.Duration;

/**
 * 大模型异常转换工具类
 * 将底层 HTTP 请求、SDK、网络原生异常统一封装为业务标准 LlmException，并分配对应错误码 ResultCode
 */
public final class LlmErrorMapper {

    /**
     * 私有构造，禁止实例化工具类
     */
    private LlmErrorMapper() {}

    /**
     * 根据HTTP响应状态码与响应体转换为标准大模型异常
     * @param status HTTP响应状态码
     * @param body 上游接口返回响应报文
     * @param latency 请求耗时
     * @return 归一化后的业务异常
     */
    public static LlmException fromHttp(int status, String body, Duration latency) {
        return switch (status / 100) {
            case 4 -> new LlmException(ResultCode.LLM_INVALID_REQUEST, "上游 4xx: " + summarize(body));
            case 5 -> new LlmException(ResultCode.LLM_PROVIDER_ERROR, "上游 5xx: " + summarize(body));
            default -> new LlmException(ResultCode.LLM_PROVIDER_ERROR, "未预期 HTTP 状态: " + status);
        };
    }

    /**
     * 请求超时异常转换
     * @param d 配置的超时时长
     * @return 超时标准异常
     */
    public static LlmException fromTimeout(java.time.Duration d) {
        return new LlmException(ResultCode.LLM_UPSTREAM_TIMEOUT, "上游响应超过 " + d.toMillis() + "ms");
    }

    /**
     * 网络连接失败异常转换（如域名不通、端口不可达）
     * @param ex 原生连接异常
     * @return 配置/网络连通性异常
     */
    public static LlmException fromConnect(ConnectException ex) {
        return new LlmException(ResultCode.LLM_CONFIG_MISSING, "无法连接上游: " + ex.getMessage());
    }

    /**
     * 截取响应体摘要，避免超长日志
     * 超过200字符自动截断并添加省略号
     * @param body 原始响应报文
     * @return 简短摘要文本
     */
    private static String summarize(String body) {
        if (body == null) return "";
        return body.length() > 200 ? body.substring(0, 200) + "…" : body;
    }
}