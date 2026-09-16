package cn.nextdev.lightboot.redis.config;

import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 模块自动配置条件装配回归测试。
 *
 * <p>用 {@link ApplicationContextRunner} 验证关键 {@code @ConditionalOnProperty} 门控：
 * <ul>
 *   <li>{@code light-boot.redis.enabled=false} 关闭模块时，可观测性记录器不装配；</li>
 *   <li>启用模块且类路径存在 {@code MeterRegistry}（test 已传递引入 actuator/micrometer）时，
 *       {@link RedisMetricsRecorder} 装配——回归 {@link RedisObservabilityAutoConfiguration} 的
 *       {@code light-boot.redis.enabled} 门控（此前该配置缺失此门控，P1 #1 已修复）。</li>
 * </ul>
 *
 * <p>注：本测试不验证 {@code RedisTemplate}/{@code RedisCacheManager} 等需要真实
 * {@code RedisConnectionFactory} 的 Bean，只验证纯条件装配逻辑（不依赖外部 Redis）。
 */
class RedisAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisObservabilityAutoConfiguration.class));

    /**
     * 启用模块且容器中存在 {@code MeterRegistry}：{@link RedisMetricsRecorder} 装配。
     *
     * <p>提供 {@link SimpleMeterRegistry} Bean 以满足构造注入
     * （{@code @ConditionalOnClass(MeterRegistry)} 在测试类路径已满足，故配置类本身会加载）。
     */
    @Test
    void enabledWithMeterRegistry_assemblesMetricsRecorder() {
        contextRunner
                .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).hasSingleBean(RedisMetricsRecorder.class));
    }

    /**
     * {@code light-boot.redis.enabled=false}：即使类路径有 Micrometer，{@link RedisMetricsRecorder} 也不装配
     * （回归 {@link RedisObservabilityAutoConfiguration} 的 {@code light-boot.redis.enabled} 门控）。
     */
    @Test
    void moduleDisabled_metricsRecorderNotAssembled() {
        contextRunner.withPropertyValues("light-boot.redis.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RedisMetricsRecorder.class));
    }
}
