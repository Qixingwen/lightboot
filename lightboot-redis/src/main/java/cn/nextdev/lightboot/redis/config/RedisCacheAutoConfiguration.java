package cn.nextdev.lightboot.redis.config;

import cn.nextdev.lightboot.redis.serializer.RedisJsonSerializerFactory;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存管理器自动配置。
 *
 * <p>在 {@link RedisTemplateAutoConfiguration} 之后执行，自动装配 {@link RedisCacheManager}，
 * 并通过 {@link CachingConfigurer} 将 {@link CacheErrorHandler} 接入 Spring 缓存拦截链，
 * 使缓存读写/清除异常仅记录日志而不向业务抛出（引入方自定义 {@link CachingConfigurer} 时自动退避）。
 * 支持默认 TTL、Key 前缀、空值缓存、按缓存名独立配置 TTL。
 *
 * <p>可通过 {@code light-boot.redis.enabled=false} 关闭。
 */
@AutoConfiguration
@ConditionalOnClass({RedisTemplate.class, RedisConnectionFactory.class})
@ConditionalOnProperty(prefix = "light-boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(RedisTemplateAutoConfiguration.class)
@EnableConfigurationProperties(RedisCacheProperties.class)
@Slf4j
public class RedisCacheAutoConfiguration {

    private static final RedisJsonSerializerFactory SERIALIZER_FACTORY = new RedisJsonSerializerFactory();

    /**
     * 默认构造方法。
     */
    public RedisCacheAutoConfiguration() {
    }

    /**
     * 自定义 {@link RedisCacheManager}，支持灵活的缓存配置。
     *
     * @param connectionFactory Redis 连接工厂
     * @param cacheProperties   缓存配置属性
     * @param redisProperties   Redis 扩展配置属性
     * @return 配置完成的 {@link RedisCacheManager}
     */
    @Bean
    @ConditionalOnMissingBean(RedisCacheManager.class)
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory, RedisCacheProperties cacheProperties, RedisProperties redisProperties) {
        log.info("Initializing RedisCacheManager with properties: {}", cacheProperties);

        Assert.notNull(connectionFactory, "RedisConnectionFactory 不能为空");

        RedisCacheConfiguration defaultConfig = buildDefaultCacheConfiguration(cacheProperties, redisProperties);

        RedisCacheManager.RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig);

        if (cacheProperties.getCaches() != null && !cacheProperties.getCaches().isEmpty()) {
            Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
            cacheProperties.getCaches().forEach((cacheName, cacheProps) -> {
                RedisCacheConfiguration config = defaultConfig;
                if (cacheProps.getTimeToLive() != null) {
                    config = config.entryTtl(Duration.ofSeconds(cacheProps.getTimeToLive()));
                }
                cacheConfigurations.put(cacheName, config);
                log.info("Cache '{}' configured with TTL: {}s", cacheName, cacheProps.getTimeToLive());
            });

            if (!cacheConfigurations.isEmpty()) {
                builder.withInitialCacheConfigurations(cacheConfigurations);
            }
        }

        RedisCacheManager cacheManager = builder.build();

        log.info("RedisCacheManager initialized successfully - keyPrefix: '{}', defaultTTL: {}s, cacheNullValues: {}",
                cacheProperties.getKeyPrefix(), cacheProperties.getTimeToLive(), cacheProperties.isCacheNullValues());

        return cacheManager;
    }

    /**
     * 缓存异常处理器，Redis 异常时仅记录日志不向外抛出，防止缓存故障影响主业务。
     *
     * @return 缓存异常处理器
     */
    @Bean
    @ConditionalOnMissingBean(CacheErrorHandler.class)
    public CacheErrorHandler cacheErrorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
                log.error("Cache get error in cache '{}' with key '{}': {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCachePutError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key, Object value) {
                log.error("Cache put error in cache '{}' with key '{}': {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(@NonNull RuntimeException exception, @NonNull Cache cache, @NonNull Object key) {
                log.error("Cache evict error in cache '{}' with key '{}': {}", cache.getName(), key, exception.getMessage());
            }

            @Override
            public void handleCacheClearError(@NonNull RuntimeException exception, @NonNull Cache cache) {
                log.error("Cache clear error in cache '{}': {}", cache.getName(), exception.getMessage());
            }
        };
    }

    /**
     * 将 {@link CacheErrorHandler} 接入 Spring 缓存拦截链的 {@link CachingConfigurer}。
     *
     * <p>Spring 的 {@code CacheInterceptor} 仅通过 {@link CachingConfigurer} 获取错误处理器，
     * 单独注册的 {@link CacheErrorHandler} Bean 不会被消费；本 Bean 使上面的容错语义真正生效。
     * 引入方自定义了 {@link CachingConfigurer} 时本 Bean 自动退避。
     *
     * @param errorHandler 缓存异常处理器
     * @return 缓存配置器
     */
    @Bean
    @ConditionalOnMissingBean(CachingConfigurer.class)
    public CachingConfigurer cachingConfigurer(CacheErrorHandler errorHandler) {
        return new CachingConfigurer() {
            @Override
            public CacheErrorHandler errorHandler() {
                return errorHandler;
            }
        };
    }

    /**
     * 构建默认的 Redis 缓存配置。
     *
     * @param cacheProperties 缓存配置属性
     * @param redisProperties Redis 扩展配置属性
     * @return 默认的 {@link RedisCacheConfiguration}
     */
    RedisCacheConfiguration buildDefaultCacheConfiguration(RedisCacheProperties cacheProperties, RedisProperties redisProperties) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig();

        config = config.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer(StandardCharsets.UTF_8)));
        config = config.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(SERIALIZER_FACTORY.create(redisProperties)));

        if (!cacheProperties.isUseKeyPrefix()) {
            config = config.disableKeyPrefix();
        } else if (cacheProperties.getKeyPrefix() != null) {
            config = config.prefixCacheNameWith(cacheProperties.getKeyPrefix());
        }

        if (cacheProperties.getTimeToLive() != null) {
            config = config.entryTtl(Duration.ofSeconds(cacheProperties.getTimeToLive()));
        }

        if (!cacheProperties.isCacheNullValues()) {
            config = config.disableCachingNullValues();
        }

        return config;
    }

}
