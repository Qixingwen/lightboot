package cn.nextdev.lightboot.logging.request;

import cn.nextdev.lightboot.logging.mask.DefaultLogMasker;
import cn.nextdev.lightboot.logging.mask.LogMasker;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * RequestLogFilter 单测：验证排除路径、慢请求阈值不阻断链路、过滤器可正常实例化。
 * （日志级别选择基于耗时，难以在单测中精确断言级别；这里验证行为正确性与链路通畅。）
 */
class RequestLogFilterTest {

    private RequestLogFilter filter;

    @BeforeEach
    void setUp() {
        // 使用 List.of("/actuator/**") 的排除路径与一个慢阈值；masker 禁用（null）、不信任任何转发头（空）
        filter = new RequestLogFilter(List.of("/actuator/**"), 1000L, null, List.of());
    }

    @AfterEach
    void clearMdc() {
        org.slf4j.MDC.clear();
    }

    /**
     * 排除路径命中时应返回 true（跳过该过滤器）。
     */
    @Test
    void shouldNotFilter_returnsTrueForExcludedPath() {
        // 直接验证 shouldNotFilter 逻辑
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    /**
     * 普通路径不在排除范围内，应返回 false。
     */
    @Test
    void shouldNotFilter_returnsFalseForNormalPath() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    /**
     * 正常请求不抛异常，并继续过滤链路。
     */
    @Test
    void doFilter_normalRequestDoesNotThrowAndContinuesChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/users");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThatNoException().isThrownBy(() -> filter.doFilter(req, resp, chain));
        verify(chain, times(1)).doFilter(req, resp);
    }

    /**
     * 慢请求阈值禁用（-1）时过滤器仍正常工作。
     */
    @Test
    void doFilter_worksWhenSlowThresholdDisabled() throws Exception {
        RequestLogFilter noSlow = new RequestLogFilter(List.of(), -1L, null, List.of());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThatNoException().isThrownBy(() -> noSlow.doFilter(req, resp, chain));
        verify(chain, times(1)).doFilter(req, resp);
    }

    /**
     * 构造方法传入 null 排除路径不应抛异常（按空列表处理）。
     */
    @Test
    void constructor_acceptsNullExcludePaths() {
        RequestLogFilter f = new RequestLogFilter(null, 1000L, null, null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        assertThat(f.shouldNotFilter(req)).isFalse();  // null → 空列表，不排除任何路径
    }

    // ==================== Task 7: query string 脱敏 ====================

    /**
     * 启用 DefaultLogMasker 时，查询串中的手机号脱敏为 138****5678，
     * 且凭据 token=abc123 被整体替换为 token=******（不再泄漏 abc123）。
     */
    @Test
    void formatQueryString_masksSensitiveQueryParams() {
        LogMasker masker = new DefaultLogMasker();
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, masker, List.of());

        String masked = f.formatQueryString("phone=13812345678&token=abc123");
        assertThat(masked).contains("138****5678");
        assertThat(masked).doesNotContain("abc123");
        assertThat(masked).contains("token=******");
    }

    /**
     * masker 为 null（脱敏禁用）时，查询串原样返回（仅做 CRLF 清洗 + 截断），
     * 敏感参数不会被脱敏。
     */
    @Test
    void formatQueryString_returnsUnmaskedWhenMaskerNull() {
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, null, List.of());

        String result = f.formatQueryString("phone=13812345678&token=abc123");
        assertThat(result).contains("13812345678");
        assertThat(result).contains("abc123");
        assertThat(result).startsWith("?");
    }

    // ==================== Task 7: 受信代理 IP ====================

    /**
     * trustedProxies 为空时，即便存在 X-Forwarded-For 头也不采信，
     * 返回直连 remoteAddr，防止客户端伪造转发头。
     */
    @Test
    void resolveClientIp_ignoresForwardedHeaderWhenRemoteAddrNotTrusted() {
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, null, List.of());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("8.8.8.8");
        req.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(f.resolveClientIp(req)).isEqualTo("8.8.8.8");
    }

    /**
     * remoteAddr 命中受信代理 CIDR（10.0.0.0/8）时，采信 X-Forwarded-For 的首个 IP。
     */
    @Test
    void resolveClientIp_honorsForwardedHeaderWhenRemoteAddrTrusted() {
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, null, List.of("10.0.0.0/8"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "9.9.9.9");

        assertThat(f.resolveClientIp(req)).isEqualTo("9.9.9.9");
    }

    /**
     * remoteAddr 命中受信代理且仅有 X-Real-IP 时，采信 X-Real-IP。
     */
    @Test
    void resolveClientIp_honorsXRealIpWhenTrusted() {
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, null, List.of("10.0.0.0/8"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Real-IP", "7.7.7.7");

        assertThat(f.resolveClientIp(req)).isEqualTo("7.7.7.7");
    }

    /**
     * X-Forwarded-For 含多个 IP 时取第一个（真实客户端）。
     */
    @Test
    void resolveClientIp_takesFirstIpFromXForwardedFor() {
        RequestLogFilter f = new RequestLogFilter(List.of(), 1000L, null, List.of("10.0.0.0/8"));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "9.9.9.9, 11.11.11.11");

        assertThat(f.resolveClientIp(req)).isEqualTo("9.9.9.9");
    }
}
