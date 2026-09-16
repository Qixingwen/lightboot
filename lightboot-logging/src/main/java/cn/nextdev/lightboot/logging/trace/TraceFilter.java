package cn.nextdev.lightboot.logging.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Web 请求 TraceId 过滤器。
 *
 * <p>处理流程：
 * <ol>
 *   <li>从请求头读取上游 TraceId（支持网关/微服务透传）</li>
 *   <li>校验格式（16-64 位小写十六进制），不合法则重新生成</li>
 *   <li>写入 {@link TraceContext}（程序内获取）和响应头（前端/下游获取）</li>
 *   <li>请求结束后清理，防止线程池复用时泄漏</li>
 * </ol>
 *
 * <p>条件加载：需要 Servlet API（jakarta.servlet）在 classpath 上。
 */
public class TraceFilter extends OncePerRequestFilter {

    /**
     * TraceId 允许的最小长度。
     */
    private static final int TRACE_ID_MIN_LENGTH = 16;

    /**
     * TraceId 允许的最大长度。
     */
    private static final int TRACE_ID_MAX_LENGTH = 64;

    /**
     * 合法的 TraceId 格式：16-64 位小写十六进制（{@code a-f0-9}；大写字母视为非法并重新生成）。
     */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("^[a-f0-9]{" + TRACE_ID_MIN_LENGTH + "," + TRACE_ID_MAX_LENGTH + "}$");

    private final String headerName;

    /**
     * 创建 TraceId 过滤器。
     *
     * @param headerName TraceId 请求头名称，如 {@code X-Trace-Id}
     */
    public TraceFilter(String headerName) {
        this.headerName = headerName;
    }

    /**
     * 校验上游 TraceId（非法则重新生成），写入 {@link TraceContext} 与响应头，请求结束后清理。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = request.getHeader(headerName);
            if (!isValidTraceId(traceId)) {
                traceId = TraceContext.generate();
            }
            TraceContext.set(traceId);
            response.setHeader(headerName, traceId);

            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * 校验上游传入的 TraceId 是否合法。
     *
     * <p>防止恶意客户端注入超长字符串、特殊字符或 CRLF 换行符。
     *
     * @param traceId 待校验的 TraceId
     * @return 合法返回 true
     */
    private boolean isValidTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return false;
        }
        return TRACE_ID_PATTERN.matcher(traceId).matches();
    }
}
