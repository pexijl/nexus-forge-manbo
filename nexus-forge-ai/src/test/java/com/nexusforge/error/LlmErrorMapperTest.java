package com.nexusforge.error;

import com.nexusforge.enums.ResultCode;
import com.nexusforge.exception.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmErrorMapper 单元测试 —— 锁住当前实现的 HTTP 状态 → ResultCode 映射契约。
 *
 * <p>已识别但尚未在生产代码里覆盖:HTTP 429(上游限流)目前被归到
 * {@link ResultCode#LLM_INVALID_REQUEST}。本测试如实反映这一行为,
 * 在 TODO 修复时需要同时更新 {@code fromHttp} 的 switch(在 4xx 分支内
 * 区分 status == 429 → LLM_RATE_LIMITED)和本类对应测试。
 */
class LlmErrorMapperTest {

    @Nested
    @DisplayName("fromHttp:HTTP 状态码归类")
    class FromHttp {

        @Test
        @DisplayName("HTTP 400 → LLM_INVALID_REQUEST")
        void http_400_maps_to_invalid_request() {
            LlmException e = LlmErrorMapper.fromHttp(400, "{\"err\":\"bad\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
            assertThat(e.getMessage()).contains("上游 4xx").contains("{\"err\":\"bad\"}");
        }

        @Test
        @DisplayName("HTTP 401 → LLM_INVALID_REQUEST(目前未区分 429,见类注释)")
        void http_401_maps_to_invalid_request() {
            LlmException e = LlmErrorMapper.fromHttp(401, "{\"err\":\"unauth\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("HTTP 404 → LLM_INVALID_REQUEST")
        void http_404_maps_to_invalid_request() {
            LlmException e = LlmErrorMapper.fromHttp(404, "{\"err\":\"not found\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("HTTP 429 → LLM_INVALID_REQUEST(待优化:应独立映射 LLM_RATE_LIMITED)")
        void http_429_currently_maps_to_invalid_request_pending_429_case() {
            LlmException e = LlmErrorMapper.fromHttp(429, "{\"err\":\"slow down\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
        }

        @Test
        @DisplayName("HTTP 500 → LLM_PROVIDER_ERROR")
        void http_500_maps_to_provider_error() {
            LlmException e = LlmErrorMapper.fromHttp(500, "{\"err\":\"oops\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
            assertThat(e.getMessage()).contains("上游 5xx").contains("{\"err\":\"oops\"}");
        }

        @Test
        @DisplayName("HTTP 502 → LLM_PROVIDER_ERROR")
        void http_502_maps_to_provider_error() {
            LlmException e = LlmErrorMapper.fromHttp(502, "{\"err\":\"bad gateway\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
        }

        @Test
        @DisplayName("HTTP 503 → LLM_PROVIDER_ERROR")
        void http_503_maps_to_provider_error() {
            LlmException e = LlmErrorMapper.fromHttp(503, "{\"err\":\"unavailable\"}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
        }

        @Test
        @DisplayName("HTTP 200(意外,不该出现在错误路径)→ LLM_PROVIDER_ERROR")
        void http_200_falls_through_default_to_provider_error() {
            LlmException e = LlmErrorMapper.fromHttp(200, "{\"ok\":true}", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
            assertThat(e.getMessage()).contains("未预期 HTTP 状态").contains("200");
        }

        @Test
        @DisplayName("HTTP 301(重定向,意外)→ LLM_PROVIDER_ERROR")
        void http_301_falls_through_default_to_provider_error() {
            LlmException e = LlmErrorMapper.fromHttp(301, "", Duration.ofMillis(10));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_PROVIDER_ERROR.getCode());
            assertThat(e.getMessage()).contains("未预期 HTTP 状态").contains("301");
        }

        @Test
        @DisplayName("响应体为 null 时不抛 NPE,正常返回摘要(空串)")
        void null_body_does_not_throw_and_produces_empty_summary() {
            LlmException e = LlmErrorMapper.fromHttp(400, null, Duration.ofMillis(10));
            // 4xx 分支,summarize(null) = "" → message 仍包含前缀但 body 部分空
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_INVALID_REQUEST.getCode());
            assertThat(e.getMessage()).contains("上游 4xx");
        }

        @Test
        @DisplayName("响应体超过 200 字符会被截断并加 …")
        void long_body_is_truncated_with_ellipsis() {
            String longBody = "x".repeat(500);
            LlmException e = LlmErrorMapper.fromHttp(400, longBody, Duration.ofMillis(10));
            // 期望 message 包含 200 字符的 'x' + '…',不包含完整 500 字符
            assertThat(e.getMessage()).contains("x".repeat(200)).contains("…");
            assertThat(e.getMessage().length()).isLessThan(longBody.length() + 50);
        }

        @Test
        @DisplayName("响应体恰好 200 字符不截断(临界值,边界条件)")
        void body_at_200_chars_is_not_truncated() {
            String body = "y".repeat(200);
            LlmException e = LlmErrorMapper.fromHttp(400, body, Duration.ofMillis(10));
            // 200 不触发 > 200 分支,message 应不含 '…'
            assertThat(e.getMessage()).doesNotContain("…");
            assertThat(e.getMessage()).contains(body);
        }

        @Test
        @DisplayName("响应体 201 字符被截断(临界值)")
        void body_at_201_chars_is_truncated() {
            String body = "z".repeat(201);
            LlmException e = LlmErrorMapper.fromHttp(400, body, Duration.ofMillis(10));
            assertThat(e.getMessage()).contains("z".repeat(200)).contains("…");
        }
    }

    @Nested
    @DisplayName("fromTimeout")
    class FromTimeout {

        @Test
        @DisplayName("2 秒超时 → LLM_UPSTREAM_TIMEOUT,message 含毫秒数")
        void timeout_maps_to_upstream_timeout_with_ms() {
            LlmException e = LlmErrorMapper.fromTimeout(Duration.ofSeconds(2));
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode());
            assertThat(e.getMessage()).contains("2000ms");
        }

        @Test
        @DisplayName("0 毫秒(极小值)→ LLM_UPSTREAM_TIMEOUT,message 含 '0ms'")
        void zero_duration_maps_to_upstream_timeout() {
            LlmException e = LlmErrorMapper.fromTimeout(Duration.ZERO);
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode());
            assertThat(e.getMessage()).contains("0ms");
        }
    }

    @Nested
    @DisplayName("fromConnect")
    class FromConnect {

        @Test
        @DisplayName("ConnectException → LLM_CONFIG_MISSING(message 含上游错误信息)")
        void connect_exception_maps_to_config_missing() {
            ConnectException ex = new ConnectException("Connection refused: localhost/127.0.0.1:11434");
            LlmException e = LlmErrorMapper.fromConnect(ex);
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_CONFIG_MISSING.getCode());
            assertThat(e.getMessage()).contains("无法连接上游").contains("Connection refused");
        }

        @Test
        @DisplayName("ConnectException 默认构造(message=null)→ LLM_CONFIG_MISSING 不抛 NPE")
        void connect_exception_with_null_message_handled_safely() {
            // Java 14+ 移除了 Throwable.setMessage,Throwable.message 在构造时已固化;
            // 无参构造 ConnectException() 让 message 为 null,验证 mapper 不会因 null
            // 触发的字符串拼接而抛 NPE。
            ConnectException ex = new ConnectException();   // 默认 message = null
            LlmException e = LlmErrorMapper.fromConnect(ex);
            assertThat(e.getCode()).isEqualTo(ResultCode.LLM_CONFIG_MISSING.getCode());
            // 注:message 是 BaseException(ResultCode,detail) 拼出的 "LLM 配置缺失: ..."
            assertThat(e.getMessage()).contains("无法连接上游");
        }
    }
}
