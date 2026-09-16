package cn.nextdev.lightboot.logging.trace;

import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ScheduledTraceAspect} 单测。
 *
 * <p>验证切面为 {@code @Scheduled} 定时任务注入的 TraceId 满足纯 hex 契约、来源标记写入独立 MDC key、
 * 执行后两个 key 都被清理（防线程池复用泄漏）。
 */
class ScheduledTraceAspectTest {

    private final ScheduledTraceAspect aspect = new ScheduledTraceAspect();

    @AfterEach
    void cleanup() {
        TraceContext.clear();
        MDC.remove(TraceContext.SOURCE_MDC_KEY);
    }

    /**
     * 切面注入的 TraceId 为 32 位纯 hex（与 {@link TraceFilter} 一致，满足 {@code ^[a-f0-9]{16,64}$} 契约），
     * 且来源 MDC 标记 {@code traceSource=scheduled} 在任务执行期间可用。
     */
    @Test
    void aroundScheduled_setsPureHexTraceIdAndScheduledSource() throws Throwable {
        AtomicReference<String> traceIdDuringRun = new AtomicReference<>();
        AtomicReference<String> mdcTraceIdDuringRun = new AtomicReference<>();
        AtomicReference<String> sourceDuringRun = new AtomicReference<>();
        ProceedingJoinPoint joinPoint = stubJoinPoint(() -> {
            traceIdDuringRun.set(TraceContext.get());
            mdcTraceIdDuringRun.set(MDC.get(TraceContext.MDC_KEY));
            sourceDuringRun.set(MDC.get(TraceContext.SOURCE_MDC_KEY));
            return null;
        });

        aspect.aroundScheduled(joinPoint);

        String traceId = traceIdDuringRun.get();
        assertThat(traceId).isNotNull();
        assertThat(traceId).hasSize(32);
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceId).doesNotContain("-"); // 不再带 sched- 前缀
        // set 同时写入 ThreadLocal 与 MDC，二者一致
        assertThat(mdcTraceIdDuringRun.get()).isEqualTo(traceId);
        assertThat(sourceDuringRun.get()).isEqualTo("scheduled");
    }

    /**
     * 任务执行后，TraceId 与来源标记都从 MDC 清理，防止线程池复用泄漏。
     */
    @Test
    void aroundScheduled_clearsMdcAfterExecution() throws Throwable {
        ProceedingJoinPoint joinPoint = stubJoinPoint(() -> null);

        aspect.aroundScheduled(joinPoint);

        assertThat(TraceContext.get()).isNull();
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        assertThat(MDC.get(TraceContext.SOURCE_MDC_KEY)).isNull();
    }

    /**
     * 任务抛异常时，finally 仍清理 MDC，且异常原样上抛（不吞异常）。
     */
    @Test
    void aroundScheduled_clearsMdcAndRethrowsOnException() throws Throwable {
        IllegalStateException boom = new IllegalStateException("task failed");
        ProceedingJoinPoint joinPoint = stubJoinPoint(() -> {
            throw boom;
        });

        assertThatThrownBy(() -> aspect.aroundScheduled(joinPoint))
                .isSameAs(boom);

        assertThat(TraceContext.get()).isNull();
        assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
        assertThat(MDC.get(TraceContext.SOURCE_MDC_KEY)).isNull();
    }

    /**
     * 构造一个 proceed 时执行给定任务的 {@link ProceedingJoinPoint} mock。
     *
     * <p>{@code joinPoint.proceed()} 声明 {@code throws Throwable}，故本方法声明同；
     * proceed 的实际行为由 {@code task} 提供。
     *
     * @param task proceed 时执行的任务
     */
    private ProceedingJoinPoint stubJoinPoint(ProceedingTask task) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenAnswer(invocation -> task.run());
        return joinPoint;
    }

    /**
     * proceed 任务的函数式接口（允许抛 Exception）。
     */
    @FunctionalInterface
    private interface ProceedingTask {
        Object run() throws Exception;
    }
}
