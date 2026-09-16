package cn.nextdev.lightboot.logging.config;

import cn.nextdev.lightboot.logging.mask.LogMasker;
import cn.nextdev.lightboot.logging.mask.MaskingStartupLogger;
import cn.nextdev.lightboot.logging.request.RequestLogFilter;
import cn.nextdev.lightboot.logging.trace.MdcTaskDecorator;
import cn.nextdev.lightboot.logging.trace.ScheduledTraceAspect;
import cn.nextdev.lightboot.logging.trace.TraceFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LoggingAutoConfiguration} 条件装配回归测试。
 *
 * <p>用 {@link WebApplicationContextRunner}（Servlet 类路径齐全，使 trace/request 过滤器的
 * {@code @ConditionalOnClass(jakarta.servlet...)} 满足）验证各 {@code @ConditionalOnProperty} 门控：
 * 关闭模块、单独关闭 trace、单独关闭 mask 时，对应 Bean 正确消失、其余 Bean 仍在。
 *
 * <p>回归保护：此前这些条件写得正确但无测试断言，新增/改动条件时易悄悄回归（如限流模块曾出现的
 * 「裸 {@code @Import} 下条件不生效致静默失效」类问题）。
 */
class LoggingAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

    /**
     * 默认装配：所有 Bean 就绪。
     */
    @Test
    void defaultConfig_assemblesAllBeans() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(TraceFilter.class)
                .hasSingleBean(RequestLogFilter.class)
                .hasSingleBean(LogMasker.class)
                .hasSingleBean(MaskingStartupLogger.class)
                .hasSingleBean(ScheduledTraceAspect.class)
                .hasSingleBean(MdcTaskDecorator.class));
    }

    /**
     * {@code light-boot.logging.enabled=false}：整个模块不装配，所有 Bean 消失。
     */
    @Test
    void moduleDisabled_noBeansAssembled() {
        contextRunner.withPropertyValues("light-boot.logging.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceFilter.class);
            assertThat(context).doesNotHaveBean(RequestLogFilter.class);
            assertThat(context).doesNotHaveBean(LogMasker.class);
            assertThat(context).doesNotHaveBean(MaskingStartupLogger.class);
            assertThat(context).doesNotHaveBean(MdcTaskDecorator.class);
        });
    }

    /**
     * 单独关闭 trace：traceFilter 消失，其余（请求日志、脱敏、MDC 装饰器）仍在。
     */
    @Test
    void traceDisabled_onlyTraceFilterMissing() {
        contextRunner.withPropertyValues("light-boot.logging.trace.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(TraceFilter.class);
            assertThat(context).hasSingleBean(RequestLogFilter.class);
            assertThat(context).hasSingleBean(LogMasker.class);
            assertThat(context).hasSingleBean(MdcTaskDecorator.class);
        });
    }

    /**
     * 单独关闭请求日志：requestLogFilter 消失，trace 与脱敏仍在。
     */
    @Test
    void requestLogDisabled_onlyRequestLogFilterMissing() {
        contextRunner.withPropertyValues("light-boot.logging.request.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(RequestLogFilter.class);
            assertThat(context).hasSingleBean(TraceFilter.class);
            assertThat(context).hasSingleBean(LogMasker.class);
        });
    }

    /**
     * 单独关闭脱敏：logMasker 与 maskingStartupLogger 消失，trace/请求日志仍在。
     */
    @Test
    void maskDisabled_onlyMaskBeansMissing() {
        contextRunner.withPropertyValues("light-boot.logging.mask.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(LogMasker.class);
            assertThat(context).doesNotHaveBean(MaskingStartupLogger.class);
            assertThat(context).hasSingleBean(TraceFilter.class);
            assertThat(context).hasSingleBean(RequestLogFilter.class);
        });
    }
}
