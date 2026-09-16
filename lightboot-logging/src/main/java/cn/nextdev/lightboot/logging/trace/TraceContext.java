package cn.nextdev.lightboot.logging.trace;

import java.util.UUID;

/**
 * TraceId 上下文持有器，基于 {@link ThreadLocal} 实现线程隔离。
 *
 * <p>同时写入 {@link org.slf4j.MDC}（key: {@code traceId}），
 * 供 Logback 通过 {@code %X{traceId}} 直接输出。
 *
 * <p>生命周期由 {@link TraceFilter}（HTTP 请求）与 {@link ScheduledTraceAspect}（{@code @Scheduled}
 * 定时任务）共同管理：各自在进入时设置、finally 中清理。
 * 业务代码通过 {@link #get()} 获取当前 TraceId，用于日志关联或下游传递。
 */
public final class TraceContext {

    /**
     * 私有构造方法，防止实例化。
     */
    private TraceContext() {
    }

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * MDC 中的 key 名称，与 logback pattern 中的 {@code %X{traceId}} 对应。
     */
    public static final String MDC_KEY = "traceId";

    /**
     * TraceId 来源的 MDC key。
     *
     * <p>用于区分 TraceId 的产生来源，<b>不参与</b> TraceId 的透传与校验（TraceId 始终是纯 hex，
     * 满足 {@link TraceFilter} 的 {@code ^[a-f0-9]{16,64}$} 契约）。当前取值：
     * <ul>
     *   <li>{@code "scheduled"} —— 由 {@link ScheduledTraceAspect} 为 {@code @Scheduled} 定时任务注入；</li>
     *   <li>HTTP 请求<b>不设置</b>该 key（来源默认即视为 HTTP，无需显式标记）。</li>
     * </ul>
     * 在 logback pattern 中用 {@code %X{traceSource:-}} 按需输出；不输出不影响任何功能。
     */
    public static final String SOURCE_MDC_KEY = "traceSource";

    /**
     * 设置当前线程的 TraceId。
     *
     * @param traceId 追踪标识
     */
    public static void set(String traceId) {
        TRACE_ID.set(traceId);
        org.slf4j.MDC.put(MDC_KEY, traceId);
    }

    /**
     * 获取当前线程的 TraceId。
     *
     * @return TraceId，未设置时返回 null
     */
    public static String get() {
        return TRACE_ID.get();
    }

    /**
     * 清理当前线程的 TraceId。
     *
     * <p>必须在请求结束时调用（由 {@link TraceFilter} 的 finally 块保证），
     * 防止线程池复用时 TraceId 泄漏到下一个请求。
     */
    public static void clear() {
        TRACE_ID.remove();
        org.slf4j.MDC.remove(MDC_KEY);
    }

    /**
     * 生成新的 TraceId（32 位 UUID，去连字符）。
     *
     * @return 32 位十六进制字符串
     */
    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
