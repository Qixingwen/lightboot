package cn.nextdev.lightboot.redis.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RedisMetricsRecorder 单测：验证指标命名、tag、成功/失败分开统计。
 */
class RedisMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private RedisMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new RedisMetricsRecorder(registry);
    }

    /**
     * record 注册名为 light-boot.redis.operations 的 Timer 指标（Prometheus 导出名 light_boot_redis_operations）。
     */
    @Test
    void record_registersRedisOperationsTimer() {
        recorder.record("get", 5, true);

        Timer timer = registry.find(RedisMetricsRecorder.METRIC_NAME)
                .tag("operation", "get").tag("result", "success").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    /**
     * 成功与失败的结果作为独立 tag 分别统计。
     */
    @Test
    void record_countsSuccessAndFailureSeparately() {
        recorder.record("set", 3, true);
        recorder.record("set", 3, false);

        Timer success = registry.find(RedisMetricsRecorder.METRIC_NAME)
                .tag("operation", "set").tag("result", "success").timer();
        Timer failure = registry.find(RedisMetricsRecorder.METRIC_NAME)
                .tag("operation", "set").tag("result", "failure").timer();
        Assertions.assertNotNull(success);
        assertThat(success.count()).isEqualTo(1);
        Assertions.assertNotNull(failure);
        assertThat(failure.count()).isEqualTo(1);
    }

    /**
     * 不同的 Redis 操作作为独立 tag 分别记录。
     */
    @Test
    void record_usesDistinctTagPerOperation() {
        recorder.record("get", 1, true);
        recorder.record("incr", 1, true);

        assertThat(registry.find(RedisMetricsRecorder.METRIC_NAME).tag("operation", "get").timer()).isNotNull();
        assertThat(registry.find(RedisMetricsRecorder.METRIC_NAME).tag("operation", "incr").timer()).isNotNull();
    }
}
