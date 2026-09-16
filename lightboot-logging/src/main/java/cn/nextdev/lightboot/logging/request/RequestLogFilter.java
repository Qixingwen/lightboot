package cn.nextdev.lightboot.logging.request;

import cn.nextdev.lightboot.logging.mask.LogMasker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * HTTP 请求日志过滤器。
 *
 * <p>记录每个请求的方法、URI、查询参数、客户端 IP、响应状态和耗时。
 * 不记录请求体/响应体内容（避免大文件场景下的内存和性能问题）。
 *
 * <p>通过 {@code light-boot.logging.request.exclude-paths} 排除不需要记录的路径。
 *
 * <p>安全：
 * <ul>
 *   <li>查询参数经 {@link LogMasker} 脱敏后再记录（masker 为 null 时跳过）。</li>
 *   <li>仅当直连 {@code remoteAddr} 命中 {@code light-boot.logging.request.trusted-proxies}（CIDR）时，
 *       才依次采信 {@code X-Forwarded-For}、{@code X-Real-IP}、{@code Proxy-Client-IP}、
 *       {@code WL-Proxy-Client-IP}（见 {@link #IP_HEADER_NAMES}）；否则使用 {@code remoteAddr}，
 *       防止客户端伪造转发头。</li>
 * </ul>
 */
@Slf4j
public class RequestLogFilter extends OncePerRequestFilter {

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<String> excludePaths;
    private final long slowThresholdMs;
    private final LogMasker logMasker;
    private final List<String> trustedProxies;

    /**
     * 代理转发的 IP 头部，按优先级排列。
     */
    private static final List<String> IP_HEADER_NAMES = List.of(
            "X-Forwarded-For",
            "X-Real-IP",
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP"
    );

    /**
     * 创建请求日志过滤器。
     *
     * @param excludePaths    排除路径列表（Ant 风格）
     * @param slowThresholdMs 慢请求阈值（毫秒），{@code <=0} 表示禁用
     * @param logMasker       日志脱敏器，可为 {@code null}（脱敏禁用，查询串原样记录）
     * @param trustedProxies  受信代理 CIDR 列表，可为 {@code null} 或空（不信任任何转发头）
     */
    public RequestLogFilter(List<String> excludePaths, long slowThresholdMs, LogMasker logMasker, List<String> trustedProxies) {
        this.excludePaths = excludePaths != null ? excludePaths : List.of();
        this.slowThresholdMs = slowThresholdMs;
        this.logMasker = logMasker;
        this.trustedProxies = trustedProxies != null ? trustedProxies : List.of();
    }

    /**
     * 命中排除路径（Ant 风格）的请求跳过记录。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String pattern : excludePaths) {
            if (pathMatcher.match(pattern, uri)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 透传过滤链并在请求结束时记录出入口日志：方法、URI、（脱敏后的）查询串、客户端 IP、
     * 状态码与耗时；耗时达到或超过 {@code slowThresholdMs} 时级别提升为 WARN 并附加 SLOW 标记。
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        long startTime = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            boolean slow = slowThresholdMs > 0 && durationMs >= slowThresholdMs;
            String message = "HTTP {} {}{} from {} -> {} ({}ms){}";
            Object[] args = new Object[]{
                    request.getMethod(),
                    request.getRequestURI(),
                    formatQueryString(request.getQueryString()),
                    resolveClientIp(request),
                    response.getStatus(),
                    durationMs,
                    slow ? " [SLOW]" : ""
            };
            if (slow) {
                log.warn(message, args);
            } else if (log.isInfoEnabled()) {
                log.info(message, args);
            }
        }
    }

    /**
     * 解析客户端真实 IP。
     *
     * <p>安全策略：先取直连 {@code remoteAddr}；仅当它命中 {@code trustedProxies}（CIDR）时，
     * 才解析代理头（{@code X-Forwarded-For} / {@code X-Real-IP} 等）取首个非 {@code unknown} 值。
     * 否则直接使用 {@code remoteAddr}，避免客户端伪造转发头（IP 欺骗）。
     * {@code X-Forwarded-For} 格式为 {@code clientIP, proxy1, proxy2}，取第一个。
     *
     * @param request HTTP 请求
     * @return 客户端 IP 地址
     */
    String resolveClientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (isTrustedProxy(remote)) {
            for (String headerName : IP_HEADER_NAMES) {
                String ip = firstNonUnknown(request.getHeader(headerName));
                if (ip != null) {
                    return sanitizeIp(ip);
                }
            }
        }
        return sanitizeIp(remote);
    }

    /**
     * 清洗 IP 值中的 CRLF/制表符，防止日志注入（Log Forging）。
     *
     * <p>理论上 remoteAddr 与受信代理回写的转发头不应含 CRLF，但作为纵深防御（defense-in-depth）：
     * 若 trustedProxies 配置不当、或受信代理未剥离原始头中的 CRLF，恶意值（如 {@code "1.2.3.4\r\nFake: x"}
     * 会原样进入 {@code from {}} 日志参数）。此处统一清洗，与 {@link #formatQueryString} 的策略一致。
     *
     * @param ip 待清洗的 IP 值
     * @return 清洗后的 IP 值（CRLF/制表符替换为下划线）
     */
    private static String sanitizeIp(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.replaceAll("[\\r\\n\\t]", "_");
    }

    /**
     * 取转发头中的首个有效（非空、非 {@code unknown}）IP。
     */
    private static String firstNonUnknown(String header) {
        if (header == null || header.isBlank() || "unknown".equalsIgnoreCase(header)) {
            return null;
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个（真实客户端）
        int commaIndex = header.indexOf(',');
        return commaIndex > 0 ? header.substring(0, commaIndex).trim() : header.trim();
    }

    /**
     * 判断 IP 是否命中受信代理 CIDR 列表。
     */
    private boolean isTrustedProxy(String ip) {
        if (ip == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (String cidr : trustedProxies) {
            try {
                if (IpCidrMatcher.matches(cidr, ip)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 非法 CIDR 跳过，不影响其它规则
            }
        }
        return false;
    }

    /**
     * 格式化查询参数字符串，超长时截断。
     *
     * <p>处理顺序：CRLF 清洗 → 脱敏 → 截断 → 拼 {@code ?}。
     * 脱敏在截断之前进行，确保敏感参数（如长 token）不会因截断被切断后部分泄漏
     * （例如 token 中段恰好落在截断边界，导致前半明文残留）。
     *
     * @param queryString 原始查询参数
     * @return 格式化后的字符串
     */
    String formatQueryString(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return "";
        }
        // 清洗 CRLF/制表符，防止日志注入（Log Forging）：恶意参数可伪造日志行
        String cleaned = queryString.replaceAll("[\\r\\n\\t]", "_");
        // 脱敏（在截断前，避免敏感信息被切断后部分泄漏）
        if (logMasker != null) {
            cleaned = logMasker.mask(cleaned);
        }
        // 截断过长的查询参数，防止日志膨胀
        if (cleaned.length() > 200) {
            return "?" + cleaned.substring(0, 200) + "...(truncated)";
        }
        return "?" + cleaned;
    }
}
