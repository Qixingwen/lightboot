package cn.nextdev.lightboot.redis.config;

import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 可观测性自动配置（redis 模块部分）。
 *
 * <p><b>条件装配：</b>仅当类路径存在 {@link MeterRegistry}（引入方加了 actuator）且 redis 模块启用
 * （{@code light-boot.redis.enabled}，默认 {@code true}）时才装配 Redis 指标记录器。
 * 框架本身不引入 actuator。{@link RedisMetricsRecorder} 由本配置类条件自动装配，
 * RedisService 的写/读方法经 ObjectProvider 自动完成埋点；引入方只需提供 MeterRegistry（如引入 actuator），无需手动注入。
 *
 * <p>与模块其他配置一致受 {@code light-boot.redis.enabled} 门控：关闭 redis 模块时，
 * 即使引入了 actuator 也不注册 {@link RedisMetricsRecorder}，避免无谓的 Bean 创建。
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "light-boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisObservabilityAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public RedisObservabilityAutoConfiguration() {
    }

    /**
     * Redis 操作指标记录器。
     *
     * @param meterRegistry Micrometer 注册表
     * @return {@link RedisMetricsRecorder} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisMetricsRecorder redisMetricsRecorder(MeterRegistry meterRegistry) {
        return new RedisMetricsRecorder(meterRegistry);
    }
}
