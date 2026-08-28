package com.nexusforge.provider.support;

import com.nexusforge.config.AiProperties;
import com.nexusforge.enums.ResultCode;
import com.nexusforge.error.LlmErrorMapper;
import com.nexusforge.exception.LlmException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各 ChatModel 共享的 HTTP 客户端工厂 + 重试 + 熔断。
 *
 * <p>职责:
 * <ul>
 *   <li>按 vendor 缓存 {@link HttpClient} 实例(不同 vendor 可用不同代理 / DNS)</li>
 *   <li>{@link #executeWithRetry(String, HttpRequest, BodyHandler)} 同步重试,
 *       指数退避(initialBackoff * multiplier^n),重试上限 + 单次耗时由
 *       {@link AiProperties.Retry} 决定</li>
 *   <li>{@link CircuitBreaker} 内存态熔断,失败数超阈值后 vendor 临时下线,
 *       halfOpenAfter 后允许一次试探</li>
 * </ul>
 *
 * <p>流式响应(WebClient)不走本类的重试,流断开重连代价高,改由上层
 * {@link com.nexusforge.client.LlmClient} 直接走 fallback chain。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatModelHttpSupport {

    private final AiProperties props;
    private final ConcurrentHashMap<String, HttpClient> httpClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();

    /** 按 vendor 取出(懒创建)共享 HttpClient */
    public HttpClient httpClient(String vendor) {
        return httpClients.computeIfAbsent(vendor, v ->
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
    }

    /** 同步重试发送,失败按白名单重试 + 熔断计数 */
    public <T> HttpResponse<T> executeWithRetry(String vendor,
                                                HttpRequest req,
                                                HttpResponse.BodyHandler<T> handler) {
        CircuitState circuit = circuitFor(vendor);
        if (circuit.isOpen()) {
            throw new LlmException(ResultCode.LLM_CIRCUIT_OPEN, "vendor=" + vendor + " 熔断中");
        }
        AiProperties.Retry r = props.getRetry();
        int maxAttempts = r.getMaxAttempts();
        Duration backoff = r.getInitialBackoff();
        LlmException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<T> resp = httpClient(vendor).send(req, handler);
                int code = resp.statusCode();
                if (code / 100 == 2) {
                    circuit.recordSuccess();
                    return resp;
                }
                LlmException ex = LlmErrorMapper.fromHttp(code, bodyAsString(resp), Duration.ZERO);
                if (!isRetryable(ex) || attempt == maxAttempts) {
                    circuit.recordFailure();
                    throw ex;
                }
                last = ex;
            } catch (java.net.http.HttpTimeoutException e) {
                LlmException ex = LlmErrorMapper.fromTimeout(props.getRequestTimeout());
                if (attempt == maxAttempts) { circuit.recordFailure(); throw ex; }
                last = ex;
            } catch (ConnectException e) {
                LlmException ex = LlmErrorMapper.fromConnect(e);
                if (attempt == maxAttempts) { circuit.recordFailure(); throw ex; }
                last = ex;
            } catch (LlmException e) {
                circuit.recordFailure();
                throw e;
            } catch (Exception e) {
                LlmException ex = new LlmException(ResultCode.LLM_PROVIDER_ERROR, e.getMessage());
                if (attempt == maxAttempts) { circuit.recordFailure(); throw ex; }
                last = ex;
            }
            sleep(backoff);
            backoff = nextBackoff(backoff, r);
        }
        // 不可达,仅编译安抚
        throw last != null ? last : new LlmException(ResultCode.LLM_PROVIDER_ERROR, "未知错误");
    }

    /** 降级 / 重试白名单 */
    public static boolean isRetryable(ResultCode code) {
        return code == ResultCode.LLM_PROVIDER_ERROR
                || code == ResultCode.LLM_UPSTREAM_TIMEOUT;
    }

    /** {@link LlmException} 便捷重载:按内部 code 字段判断。
     *  避免外部调用方拿到 Integer 后再做枚举反向映射。 */
    public static boolean isRetryable(LlmException ex) {
        Integer c = ex == null ? null : ex.getCode();
        if (c == null) return false;
        return c.equals(ResultCode.LLM_PROVIDER_ERROR.getCode())
                || c.equals(ResultCode.LLM_UPSTREAM_TIMEOUT.getCode());
    }

    /** 是否整条链熔断到不可用 */
    public boolean isVendorOpen(String vendor) {
        return circuitFor(vendor).isOpen();
    }

    private CircuitState circuitFor(String vendor) {
        return circuits.computeIfAbsent(vendor, v ->
                new CircuitState(props.getCircuitBreaker()));
    }

    private static void sleep(Duration d) {
        try { Thread.sleep(d.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static Duration nextBackoff(Duration cur, AiProperties.Retry r) {
        long next = (long) (cur.toMillis() * r.getMultiplier());
        return Duration.ofMillis(Math.min(next, r.getMaxBackoff().toMillis()));
    }

    @SuppressWarnings("unchecked")
    private static <T> String bodyAsString(HttpResponse<T> resp) {
        Object body = resp.body();
        return body == null ? "" : body.toString();
    }
}