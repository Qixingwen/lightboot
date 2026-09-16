package cn.nextdev.lightboot.logging.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceContext 单测：验证 ThreadLocal 与 MDC 的联动、清理防泄漏、generate 格式。
 */
class TraceContextTest {

    @AfterEach
    void cleanup() {
        // 每个用例后清理，避免 ThreadLocal 跨用例泄漏
        TraceContext.clear();
    }

    /**
     * set 同时写入 ThreadLocal 与 MDC。
     */
    @Test
    void set_writesToThreadLocalAndMdc() {
        TraceContext.set("abc123");
        assertThat(TraceContext.get()).isEqualTo("abc123");
        assertThat(MDC.get(TraceContext.MDC_KEY)).isEqualTo("abc123");
    }

    /**
     * 未设置时 get 返回 null。
     */
    @Test
    void get_returnsNullWhenUnset() {
        assertThat(TraceContext.get()).isNull();
    }

    /**
     * clear 同时清理 ThreadLocal 与 MDC。
     */
    @Test
    void clear_clearsThreadLocalAndMdc() {
        TraceContext.set("xyz789");
        TraceContext.clear();
        assertThat(TraceContext.get()).isNull();
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
    }

    /**
     * generate 返回 32 位无连字符的十六进制 UUID。
     */
    @Test
    void generate_returns32CharHexUuidWithoutHyphens() {
        String traceId = TraceContext.generate();
        assertThat(traceId).hasSize(32);
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceId).doesNotContain("-");
    }

    /**
     * MDC_KEY 常量值为 "traceId"。
     */
    @Test
    void mdcKey_constantIsTraceId() {
        assertThat(TraceContext.MDC_KEY).isEqualTo("traceId");
    }
}
