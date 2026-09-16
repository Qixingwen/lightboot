package cn.nextdev.lightboot.logging.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * HTTP 请求指标记录器，基于 Micrometer。
 *
 * <p>记录 {@code light-boot.http.requests} Timer，tag 为 {@code method}（HTTP 方法）与 {@code status}（响应状态码）。
 * 不使用 URI 作为 tag（高基数，会导致指标爆炸）；URI 维度由日志（含 traceId）覆盖。
 *
 * <p>仅在类路径存在 {@link MeterRegistry}（即引入方加了 actuator）时由条件配置装配。
 */
public class HttpMetricsRecorder {

    /**
     * HTTP 请求耗时的指标名。
     */
    public static final String METRIC_NAME = "light-boot.http.requests";

    private final MeterRegistry meterRegistry;

    /**
     * 创建 HTTP 指标记录器。
     *
     * @param meterRegistry Micrometer 指标注册表
     */
    public HttpMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 HTTP 请求的耗时。
     *
     * @param method     HTTP 方法（GET/POST/...）
     * @param status     HTTP 响应状态码
     * @param durationMs 耗时（毫秒）
     */
    public void record(String method, int status, long durationMs) {
        Timer.builder(METRIC_NAME)
                .tag("method", method)
                .tag("status", String.valueOf(status))
                .description("HTTP request duration")
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(durationMs));
    }
}
