package cn.nextdev.lightboot.redis.config;

import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import cn.nextdev.lightboot.redis.service.FaultTolerantRedisService;
import cn.nextdev.lightboot.redis.service.RedisService;
import cn.nextdev.lightboot.redis.service.SequenceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * Redis 服务层自动配置。
 *
 * <p>在 {@link RedisTemplateAutoConfiguration} 之后执行，自动装配：
 * <ul>
 *   <li>{@link RedisService} — 统一 Redis 操作服务（自动添加 {@code light-boot.redis.key-prefix} 全局前缀）</li>
 *   <li>{@link SequenceService} — 全局序列号生成服务</li>
 * </ul>
 *
 * <p>可通过 {@code light-boot.redis.enabled=false} 关闭整个模块。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "light-boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(RedisTemplateAutoConfiguration.class)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisServiceAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public RedisServiceAutoConfiguration() {
    }

    /**
     * 注册统一 Redis 操作服务。
     *
     * <p>注入可选的 {@link RedisMetricsRecorder} provider（仅当引入方装配 actuator 时存在），
     * 用于在 RedisService 关键读写方法记录操作耗时与成败，无 actuator 时为 null 安全（不记录）。
     *
     * @param redisTemplate           框架提供的 {@code RedisTemplate<String, Object>}
     * @param properties              Redis 扩展配置属性，提供全局 Key 前缀
     * @param metricsRecorderProvider Redis 指标记录器 provider，可为空
     * @return {@link RedisService} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public RedisService redisService(RedisTemplate<String, Object> redisTemplate, RedisProperties properties, ObjectProvider<RedisMetricsRecorder> metricsRecorderProvider) {
        if (properties.isFaultTolerant()) {
            return new FaultTolerantRedisService(redisTemplate, properties.getKeyPrefix(), metricsRecorderProvider);
        }
        return new RedisService(redisTemplate, properties.getKeyPrefix(), metricsRecorderProvider);
    }

    /**
     * 注册全局序列号生成服务。
     *
     * @param redisTemplate 框架提供的 {@code RedisTemplate<String, Object>}
     * @param properties    Redis 扩展配置属性，提供 Key 前缀
     * @return {@link SequenceService} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    public SequenceService sequenceService(RedisTemplate<String, Object> redisTemplate, RedisProperties properties) {
        return new SequenceService(redisTemplate, properties.getKeyPrefix());
    }

}
