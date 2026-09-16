package cn.nextdev.lightboot.logging.config;

import cn.nextdev.lightboot.logging.mask.DefaultLogMasker;
import cn.nextdev.lightboot.logging.mask.LogMasker;
import cn.nextdev.lightboot.logging.mask.LoggingContextHolder;
import cn.nextdev.lightboot.logging.mask.MaskingStartupLogger;
import cn.nextdev.lightboot.logging.request.RequestLogFilter;
import cn.nextdev.lightboot.logging.trace.MdcTaskDecorator;
import cn.nextdev.lightboot.logging.trace.ScheduledTraceAspect;
import cn.nextdev.lightboot.logging.trace.TraceFilter;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskDecorator;

/**
 * 日志模块自动配置。
 *
 * <p>装配内容（类内 {@code @Bean} 声明顺序；过滤器之间另以 {@code @Order} 精确排序，
 * 各 Bean 是否生效由其自身的 {@code @ConditionalOn*} 决定）：
 * <ol>
 *   <li>loggingContextHolderInitializer — 注入 Spring 上下文到 LoggingContextHolder（供 Logback Converter 桥接）</li>
 *   <li>traceFilter — 注入 TraceId（最先执行，确保后续组件可获取）</li>
 *   <li>requestLogFilter — 记录请求日志（需要 TraceId 已就绪）</li>
 *   <li>logMasker — 脱敏实现（供 Logback Converter 使用）</li>
 *   <li>maskingStartupLogger — 脱敏接线自检（启动完成时提醒一次）</li>
 *   <li>scheduledTraceAspect — 定时任务 TraceId 切面（需要 AOP 依赖，无则不加载）</li>
 *   <li>mdcTaskDecorator — @Async 线程池的 MDC 传播装饰器</li>
 * </ol>
 *
 * <p>可通过 {@code light-boot.logging.enabled=false} 关闭本配置类（即上述全部 Bean）；
 * HTTP 指标采集由 {@link LoggingObservabilityAutoConfiguration} 单独装配，
 * 仅受 MeterRegistry 类路径与本开关控制。
 */
@AutoConfiguration
@EnableConfigurationProperties(LoggingProperties.class)
@ConditionalOnProperty(prefix = "light-boot.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public LoggingAutoConfiguration() {
    }

    /**
     * 容器关闭时清除 {@link LoggingContextHolder} 持有的静态 ApplicationContext 引用，
     * 防止在多次上下文刷新或集成测试场景下出现静态字段泄漏。
     */
    @PreDestroy
    public void clearContextHolder() {
        LoggingContextHolder.clear();
    }

    /**
     * 将 Spring ApplicationContext 注入静态持有器，
     * 供 Logback Converter 等 non-Spring 组件获取容器中的 Bean。
     *
     * @param applicationContext Spring 应用上下文
     * @return 占位 Bean
     */
    @Bean
    public Object loggingContextHolderInitializer(ApplicationContext applicationContext) {
        LoggingContextHolder.setApplicationContext(applicationContext);
        return new LoggingContextHolderInitializer();
    }

    /**
     * TraceId 过滤器（需要 Servlet API）。
     *
     * @param properties 日志配置属性
     * @return TraceFilter 实例
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    @ConditionalOnProperty(prefix = "light-boot.logging.trace", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TraceFilter traceFilter(LoggingProperties properties) {
        return new TraceFilter(properties.getTrace().getHeaderName());
    }

    /**
     * 请求日志过滤器（需要 Servlet API）。
     *
     * @param properties        日志配置属性
     * @param logMaskerProvider 脱敏器 provider（可选，未装配时为 {@code null}，查询串原样记录）
     * @return RequestLogFilter 实例
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    @ConditionalOnProperty(prefix = "light-boot.logging.request", name = "enabled", havingValue = "true", matchIfMissing = true)
    public RequestLogFilter requestLogFilter(LoggingProperties properties, org.springframework.beans.factory.ObjectProvider<LogMasker> logMaskerProvider) {
        LogMasker masker = logMaskerProvider.getIfAvailable();
        return new RequestLogFilter(
                properties.getRequest().getExcludePaths(),
                properties.getRequest().getSlowThresholdMs(),
                masker,
                properties.getRequest().getTrustedProxies());
    }

    /**
     * 脱敏实现。
     *
     * @return DefaultLogMasker 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "light-boot.logging.mask", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LogMasker logMasker() {
        return new DefaultLogMasker();
    }

    /**
     * 启动告警监听器：提醒运维脱敏默认不生效，需在 {@code logback-spring.xml} 手动接线 {@code %maskMsg}/{@code %maskEx}。
     *
     * <p>仅在脱敏开启时装配；使用方可注册自己的同名 Bean 覆盖以消音。
     *
     * @return MaskingStartupLogger 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "light-boot.logging.mask", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MaskingStartupLogger maskingStartupLogger() {
        return new MaskingStartupLogger();
    }

    /**
     * 定时任务 TraceId 切面（需要 AOP 依赖）。
     *
     * @return ScheduledTraceAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    public ScheduledTraceAspect scheduledTraceAspect() {
        return new ScheduledTraceAspect();
    }

    /**
     * MDC 上下文传播 TaskDecorator（spring-core 中的 {@link TaskDecorator} 始终在 classpath）。
     *
     * <p>挂载到异步线程池后，会把提交线程的 traceId 等 MDC 传播到子线程，
     * 并在子线程执行后恢复其原有 MDC（线程池复用安全）。业务模块可通过注入
     * 该 Bean 复用同一传播逻辑，避免各自重复实现。
     *
     * @return MdcTaskDecorator 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public MdcTaskDecorator mdcTaskDecorator() {
        return new MdcTaskDecorator();
    }

    /**
     * 占位 Bean，仅用于触发 LoggingContextHolder 初始化。
     * 不暴露公共 API。
     */
    private static class LoggingContextHolderInitializer {
    }
}
