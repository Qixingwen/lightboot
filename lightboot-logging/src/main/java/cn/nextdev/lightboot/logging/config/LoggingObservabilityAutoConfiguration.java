package cn.nextdev.lightboot.logging.config;

import cn.nextdev.lightboot.logging.metrics.HttpMetricsFilter;
import cn.nextdev.lightboot.logging.metrics.HttpMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 可观测性自动配置（logging 模块部分）。
 *
 * <p><b>条件装配：</b>仅当类路径存在 {@link MeterRegistry}（引入方加了 spring-boot-starter-actuator）
 * 且 {@code light-boot.logging.enabled != false} 时才装配 HTTP 指标记录器。框架本身不引入 actuator。
 *
 * <p>同时注册一个 {@link cn.nextdev.lightboot.logging.metrics.HttpMetricsFilter}，
 * 在请求结束时调用 {@link HttpMetricsRecorder#record} 记录耗时。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "light-boot.logging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LoggingObservabilityAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public LoggingObservabilityAutoConfiguration() {
    }

    /**
     * HTTP 指标记录器。
     *
     * @param meterRegistry Micrometer 注册表（由 actuator 自动提供）
     * @return HttpMetricsRecorder 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpMetricsRecorder httpMetricsRecorder(MeterRegistry meterRegistry) {
        return new HttpMetricsRecorder(meterRegistry);
    }

    /**
     * HTTP 指标采集过滤器（记录每个请求的耗时到指标；需要 Servlet API 在 classpath 上）。
     *
     * @param recorder HTTP 指标记录器
     * @return HttpMetricsFilter 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @Order(Ordered.HIGHEST_PRECEDENCE + 11)
    @ConditionalOnClass(name = "jakarta.servlet.http.HttpServletRequest")
    public HttpMetricsFilter httpMetricsFilter(HttpMetricsRecorder recorder) {
        return new HttpMetricsFilter(recorder);
    }
}
