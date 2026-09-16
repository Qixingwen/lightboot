package cn.nextdev.lightboot.logging.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpMetricsRecorder 单测：用 SimpleMeterRegistry 验证指标被正确注册与记录。
 */
class HttpMetricsRecorderTest {

    private SimpleMeterRegistry registry;
    private HttpMetricsRecorder recorder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new HttpMetricsRecorder(registry);
    }

    /**
     * record 注册名为 light-boot.http.requests 的 Timer 指标（Prometheus 导出名追加基准单位后缀：light_boot_http_requests_seconds 系列）。
     */
    @Test
    void record_registersHttpRequestsTimer() {
        recorder.record("GET", 200, 50);

        Timer timer = registry.find(HttpMetricsRecorder.METRIC_NAME).tag("method", "GET").tag("status", "200").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    /**
     * 多次调用累加 count。
     */
    @Test
    void record_accumulatesCountAcrossCalls() {
        recorder.record("GET", 200, 50);
        recorder.record("GET", 200, 30);
        recorder.record("POST", 500, 100);

        Timer get200 = registry.find(HttpMetricsRecorder.METRIC_NAME).tag("method", "GET").tag("status", "200").timer();
        Timer post500 = registry.find(HttpMetricsRecorder.METRIC_NAME).tag("method", "POST").tag("status", "500").timer();
        Assertions.assertNotNull(get200);
        assertThat(get200.count()).isEqualTo(2);
        Assertions.assertNotNull(post500);
        assertThat(post500.count()).isEqualTo(1);
    }

    /**
     * 不同 HTTP 状态码作为独立 tag 分别记录。
     */
    @Test
    void record_usesDistinctTagPerStatusCode() {
        recorder.record("GET", 200, 10);
        recorder.record("GET", 404, 20);

        assertThat(registry.find(HttpMetricsRecorder.METRIC_NAME).tag("status", "200").timer()).isNotNull();
        assertThat(registry.find(HttpMetricsRecorder.METRIC_NAME).tag("status", "404").timer()).isNotNull();
    }
}
