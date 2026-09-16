package cn.nextdev.lightboot.redis.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Redis 缓存自动配置回归测试。
 *
 * <p>验证两项此前 Javadoc 与实现不符的问题已被真正修复：
 * <ul>
 *   <li>{@link RedisCacheAutoConfiguration} 注册的 {@link CacheErrorHandler} 必须通过
 *       {@link CachingConfigurer} 接入 Spring 缓存拦截链——裸 {@code CacheErrorHandler} Bean
 *       从不被 {@code CacheInterceptor} 消费，「缓存故障仅记日志不抛出」的承诺不会生效；</li>
 *   <li>{@code light-boot.cache.redis.use-key-prefix=false} 必须真正禁用 Key 前缀
 *       （{@link org.springframework.data.redis.cache.RedisCacheConfiguration#disableKeyPrefix()}），
 *       而不是仅跳过 {@code prefixCacheNameWith} 导致 {@code cacheName::} 分隔符仍然存在。</li>
 * </ul>
 */
class RedisCacheAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisCacheAutoConfiguration.class))
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
            // RedisProperties 正常由 RedisTemplateAutoConfiguration 的 @EnableConfigurationProperties 注册，
            // 本 runner 未引入该自动配置，直接提供默认实例
            .withBean(RedisProperties.class, RedisProperties::new);

    /**
     * {@link CacheErrorHandler} 必须能通过容器中的 {@link CachingConfigurer} 被缓存拦截链取到，
     * 且两者指向同一实例（缓存故障仅记日志的容错语义真正生效）。
     */
    @Test
    void cacheErrorHandler_isWiredThroughCachingConfigurer() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CachingConfigurer.class);
            CacheErrorHandler handler = context.getBean(CacheErrorHandler.class);
            assertThat(context.getBean(CachingConfigurer.class).errorHandler()).isSameAs(handler);
        });
    }

    /**
     * 引入方自定义 {@link CachingConfigurer} 时，自动配置的配置器必须退避（不覆盖用户配置）。
     */
    @Test
    void userDefinedCachingConfigurer_isNotOverridden() {
        CachingConfigurer userDefined = new CachingConfigurer() {
        };
        contextRunner
                .withBean("userCachingConfigurer", CachingConfigurer.class, () -> userDefined)
                .run(context -> assertThat(context.getBean(CachingConfigurer.class)).isSameAs(userDefined));
    }

    /**
     * {@code use-key-prefix=false}：默认缓存配置的前缀必须被关闭（{@code usePrefix()} 为 false）。
     */
    @Test
    void useKeyPrefixFalse_disablesPrefix() {
        RedisCacheProperties properties = new RedisCacheProperties();
        properties.setUseKeyPrefix(false);

        org.springframework.data.redis.cache.RedisCacheConfiguration config = new RedisCacheAutoConfiguration()
                .buildDefaultCacheConfiguration(properties, new RedisProperties());

        assertThat(config.usePrefix()).isFalse();
    }
}
