package cn.nextdev.lightboot.redis.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Redis 操作指标记录器，基于 Micrometer。
 *
 * <p>记录 {@code light-boot.redis.operations} Timer，tag 为 {@code operation}（操作名，如 get/set/incr）
 * 与 {@code result}（success/failure）。该记录器由 RedisObservabilityAutoConfiguration 条件自动装配，
 * RedisService 的写/读方法经 ObjectProvider 自动完成埋点；引入方只需提供 MeterRegistry（如引入 actuator），无需手动注入。
 *
 * <p>仅在类路径存在 {@link MeterRegistry}（引入方加了 actuator）时由条件配置装配。
 */
public class RedisMetricsRecorder {

    /**
     * Redis 操作耗时的指标名。
     */
    public static final String METRIC_NAME = "light-boot.redis.operations";

    private final MeterRegistry meterRegistry;

    /**
     * 创建 Redis 指标记录器。
     *
     * @param meterRegistry Micrometer 指标注册表
     */
    public RedisMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次 Redis 操作的耗时与结果。
     *
     * @param operation  操作名（get/set/incr/delete 等）
     * @param durationMs 耗时（毫秒）
     * @param success    是否成功
     */
    public void record(String operation, long durationMs, boolean success) {
        Timer.builder(METRIC_NAME)
                .tag("operation", operation)
                .tag("result", success ? "success" : "failure")
                .description("Redis operation duration")
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(durationMs));
    }
}
