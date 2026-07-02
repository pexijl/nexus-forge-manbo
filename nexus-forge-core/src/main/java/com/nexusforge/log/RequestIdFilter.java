package com.nexusforge.log;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 请求链路追踪 ID 过滤器
 * <p>
 * 功能描述：
 * 1. 为每个 HTTP 请求生成或提取全局唯一的 TraceId，用于分布式链路追踪
 * 2. 从请求头 {@value #HEADER} 中获取 TraceId，若无则自动生成 UUID
 * 3. 将 TraceId 存入 MDC（Mapped Diagnostic Context），供整个请求链路中的日志统一使用
 * 4. 将 TraceId 写入响应头 {@value #HEADER}，便于客户端或下游服务获取
 * 5. 记录每个 HTTP 请求的访问日志（方法、URI、状态码、耗时）
 * <p>
 * 执行顺序：
 * 使用 {@code @Order(Ordered.HIGHEST_PRECEDENCE)} 确保该过滤器最先执行，
 * 保证后续所有组件（包括其他过滤器、拦截器、Controller）都能获取到 TraceId
 * <p>
 * 使用场景：
 * - 微服务架构中的请求链路追踪
 * - 日志聚合与关联分析（如 ELK、Splunk）
 * - 线上问题定位与性能诊断
 * <p>
 * 注意事项：
 * - 请求处理完成后会通过 MDC.remove() 清理 TraceId，避免线程复用导致上下文污染
 * - 若上游服务传递了 X-Trace-Id，则复用该值，否则自动生成
 * - 日志输出依赖 MDC，需在 logback/log4j2 配置文件中配置 %X{traceId} 格式
 *
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /**
     * HTTP 请求/响应头名称，用于传递 TraceId
     */
    public static final String HEADER = "X-Trace-Id";
    /**
     * MDC 上下文中的键名，用于存储 TraceId
     */
    public static final String MDC_KEY = "traceId";

    /**
     * 执行请求过滤处理
     * <p>
     * 处理流程：
     * 1. 从请求头获取 TraceId，若无则生成 UUID
     * 2. 将 TraceId 存入 MDC 和响应头
     * 3. 记录请求执行耗时
     * 4. 执行后续过滤器链
     * 5. 清理 MDC 中的 TraceId，避免上下文污染
     *
     * @param req    HTTP 请求对象
     * @param res    HTTP 响应对象
     * @param chain  过滤器链，用于传递请求到下一个处理节点
     * @throws ServletException 处理请求时发生的 Servlet 异常
     * @throws IOException      处理请求时发生的 IO 异常
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain
    ) throws ServletException, IOException {
        // 获取或生成 TraceId
        String traceId = Optional.ofNullable(req.getHeader(HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", ""));
        // 存入 MDC 和响应头
        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            // 记录请求访问日志
            long cost = System.currentTimeMillis() - start;
            log.info("HTTP {} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(), cost);
            // 清理 MDC，防止线程复用导致上下文污染
            MDC.remove(MDC_KEY);
        }
    }
}
