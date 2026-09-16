package cn.nextdev.lightboot.logging.metrics;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 指标采集过滤器，在请求结束时将耗时记录到 {@link HttpMetricsRecorder}。
 *
 * <p>仅记录指标（method/status/耗时），不记日志——日志由 {@code RequestLogFilter} 负责。
 * 与 RequestLogFilter 并存，互不依赖。
 *
 * <p>条件加载：需要 actuator（{@code MeterRegistry}）与 Servlet API（jakarta.servlet）都在 classpath 上。
 */
public class HttpMetricsFilter extends OncePerRequestFilter {

    private final HttpMetricsRecorder recorder;

    /**
     * 创建指标采集过滤器。
     *
     * @param recorder HTTP 指标记录器
     */
    public HttpMetricsFilter(HttpMetricsRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * 透传过滤链，并在请求结束时（finally）记录 method/status/耗时到指标。
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            recorder.record(request.getMethod(), response.getStatus(), durationMs);
        }
    }
}
