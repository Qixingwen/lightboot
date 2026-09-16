package cn.nextdev.lightboot.logging.trace;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;

/**
 * 定时任务 TraceId 注入切面。
 *
 * <p>拦截所有 {@code @Scheduled} 方法，在执行前自动生成 TraceId 并写入 MDC，
 * 执行结束后清理，确保定时任务的日志可通过 TraceId 关联。
 *
 * <p><b>TraceId 格式：</b>使用 {@link TraceContext#generate()} 生成 32 位纯 hex，
 * 与 {@link TraceFilter}（HTTP 请求）的 TraceId 完全一致，满足 {@code ^[a-f0-9]{16,64}$} 契约。
 * 这样定时任务发起的下游 HTTP 调用透传 {@code X-Trace-Id} 时，下游 {@code TraceFilter} 能正确接受，
 * 链路在「定时任务 → HTTP」边界不会断裂。
 *
 * <p><b>来源标记：</b>另以独立 MDC key {@link TraceContext#SOURCE_MDC_KEY}（{@code traceSource}）写入
 * {@code "scheduled"}，用于在日志中区分 TraceId 来源。该 key 不参与 TraceId 的透传与校验，
 * 想在日志输出来源时在 logback pattern 中加 {@code %X{traceSource:-}}，不加也不影响任何功能。
 *
 * <p>此切面需要 {@code spring-boot-starter-aspectj} 依赖（Spring Boot 4 中原 {@code spring-boot-starter-aop} 已更名）。
 * 已在 {@link cn.nextdev.lightboot.logging.config.LoggingAutoConfiguration} 中
 * 通过 {@code @Bean} + {@code @ConditionalOnClass} 条件注册，无 AOP 依赖时自动跳过。
 * {@code @Aspect} 本身不会被 Spring 自动代理，必须通过 {@code @Bean} 或 {@code @Component} 注册方可生效。
 */
@Aspect
public class ScheduledTraceAspect {

    /**
     * 默认构造方法。
     */
    public ScheduledTraceAspect() {
    }

    /**
     * 环绕通知：为定时任务自动注入纯 hex TraceId，并标记来源为 {@code scheduled}。
     *
     * @param joinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常
     */
    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object aroundScheduled(ProceedingJoinPoint joinPoint) throws Throwable {
        try {
            TraceContext.set(TraceContext.generate());
            MDC.put(TraceContext.SOURCE_MDC_KEY, "scheduled");
            return joinPoint.proceed();
        } finally {
            TraceContext.clear();
            MDC.remove(TraceContext.SOURCE_MDC_KEY);
        }
    }
}
