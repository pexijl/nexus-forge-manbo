package com.nexusforge.filter;

import com.nexusforge.chat.StreamAuthorizationPolicy;
import com.nexusforge.config.JwtProperties;
import com.nexusforge.enums.TokenType;
import com.nexusforge.service.AuthService;
import com.nexusforge.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtQueryTokenFilter 单元测试 —— 用 Spring 的 Mock Servlet API 直接驱动 filter,
 * 不需要完整的 Spring 上下文;依赖 JwtUtil / JwtProperties / AuthService 全部 mock。
 *
 * <p>覆盖:
 * <ul>
 *   <li>{@code shouldNotFilter}:非 SSE 路径或 ALLOW_QUERY_TOKEN=false 时跳过</li>
 *   <li>SSE 路径无 token → chain 放行,Authentication 未设置</li>
 *   <li>SSE 路径有效 access token → Authentication 设置到 SecurityContext</li>
 *   <li>SSE 路径无效 token → chain 放行,Authentication 未设置(主 filter 收尾)</li>
 *   <li>SSE 路径 refresh token → 拒绝(仅 access 走业务)</li>
 *   <li>SSE 路径黑名单 token → 拒绝,clearContext</li>
 * </ul>
 */
class JwtQueryTokenFilterTest {

    private JwtUtil jwtUtil;
    private JwtProperties jwtProps;
    private AuthService authService;
    private JwtQueryTokenFilter filter;

    @BeforeEach
    void setUp() {
        jwtUtil = mock(JwtUtil.class);
        jwtProps = new JwtProperties();              // 默认 enableBlacklist=true
        authService = mock(AuthService.class);
        filter = new JwtQueryTokenFilter(jwtUtil, jwtProps, authService);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 构造一个 SSE 路径的 mock 请求,带可选 query token */
    private MockHttpServletRequest sseRequest(String queryToken) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/chat/stream");
        req.setRequestURI("/api/ai/chat/stream");
        if (queryToken != null) {
            req.addParameter(StreamAuthorizationPolicy.QUERY_PARAM, queryToken);
        }
        return req;
    }

    private MockHttpServletRequest otherRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ai/chat");
        req.setRequestURI("/api/ai/chat");
        req.addParameter(StreamAuthorizationPolicy.QUERY_PARAM, "anything");
        return req;
    }

    /** 给定 Claims,默认 jti=sub-123 / username=alice / typ=access */
    private Claims fakeClaims() {
        Claims c = mock(Claims.class);
        when(c.getSubject()).thenReturn("123");
        when(c.getId()).thenReturn("jti-1");
        when(c.get("username", String.class)).thenReturn("alice");
        return c;
    }

    // ─── shouldNotFilter 行为 ──────────────────────────────────────
    @Nested
    @DisplayName("shouldNotFilter 短路逻辑")
    class ShouldNotFilter {

        @Test
        @DisplayName("非 SSE 路径(即便 query 携带 token)→ shouldNotFilter = true,filter 整体跳过")
        void non_sse_path_is_skipped() throws Exception {
            MockHttpServletRequest req = otherRequest();
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            // shouldNotFilter 返回 true 表示"应当跳过 filter 内部逻辑"
            assertThat(filter.shouldNotFilter(req)).isTrue();
            filter.doFilter(req, resp, chain);

            // 即便手动调用 doFilter,jwtUtil 也完全没被调用 → filter 内部被短路
            verify(jwtUtil, never()).validateToken(anyString());
            verify(chain, times(1)).doFilter(req, resp);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("SSE 路径 → shouldNotFilter = false,filter 实际执行")
        void sse_path_runs_filter() throws Exception {
            MockHttpServletRequest req = sseRequest(null);    // 无 token
            assertThat(filter.shouldNotFilter(req)).isFalse();
        }
    }

    // ─── token 处理路径 ────────────────────────────────────────────
    @Nested
    @DisplayName("SSE 路径 token 处理")
    class TokenHandling {

        @Test
        @DisplayName("SSE 路径 + 无 token → chain 放行,Authentication 未设置")
        void sse_without_token_passes_through_without_auth() throws Exception {
            MockHttpServletRequest req = sseRequest(null);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            verify(jwtUtil, never()).validateToken(anyString());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("SSE 路径 + 有效 access token → SecurityContextHolder 设置 Authentication")
        void sse_with_valid_access_token_sets_auth() throws Exception {
            String token = "valid-access-token";
            Claims claims = fakeClaims();
            when(jwtUtil.validateToken(token)).thenReturn(true);
            when(jwtUtil.parseToken(token)).thenReturn(claims);
            when(jwtUtil.extractType(claims)).thenReturn(TokenType.ACCESS);
            when(authService.isBlacklisted(claims)).thenReturn(false);

            MockHttpServletRequest req = sseRequest(token);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).isEmpty();        // 当前 SSE 不加载 RBAC
            assertThat(auth.getPrincipal()).extracting("userId").isEqualTo(123L);
            assertThat(auth.getPrincipal()).extracting("username").isEqualTo("alice");
        }

        @Test
        @DisplayName("SSE 路径 + 无效 token → chain 放行,Authentication 未设置(主 filter 收尾)")
        void sse_with_invalid_token_passes_through() throws Exception {
            String token = "garbage";
            when(jwtUtil.validateToken(token)).thenReturn(false);

            MockHttpServletRequest req = sseRequest(token);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            verify(jwtUtil, never()).parseToken(anyString());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("SSE 路径 + refresh token → 拒绝(只放行 access)")
        void sse_with_refresh_token_rejected() throws Exception {
            String token = "refresh-token";
            Claims claims = fakeClaims();
            when(jwtUtil.validateToken(token)).thenReturn(true);
            when(jwtUtil.parseToken(token)).thenReturn(claims);
            when(jwtUtil.extractType(claims)).thenReturn(TokenType.REFRESH);

            MockHttpServletRequest req = sseRequest(token);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            verify(authService, never()).isBlacklisted(any());        // 黑名单检查在 type 校验之后,refresh 提前退出
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("SSE 路径 + 黑名单 access token → 拒绝,clearContext")
        void sse_with_blacklisted_token_rejected() throws Exception {
            String token = "blacklisted-access";
            Claims claims = fakeClaims();
            when(jwtUtil.validateToken(token)).thenReturn(true);
            when(jwtUtil.parseToken(token)).thenReturn(claims);
            when(jwtUtil.extractType(claims)).thenReturn(TokenType.ACCESS);
            when(authService.isBlacklisted(claims)).thenReturn(true);

            MockHttpServletRequest req = sseRequest(token);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("SSE 路径 + parseToken 抛异常 → catch 住,clearContext,chain 放行")
        void sse_with_parse_exception_passes_through() throws Exception {
            String token = "valid-syntax-but-payload-broken";
            when(jwtUtil.validateToken(token)).thenReturn(true);
            when(jwtUtil.parseToken(token)).thenThrow(new RuntimeException("payload decode failed"));

            MockHttpServletRequest req = sseRequest(token);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(req, resp, chain);

            verify(chain, times(1)).doFilter(req, resp);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }
}