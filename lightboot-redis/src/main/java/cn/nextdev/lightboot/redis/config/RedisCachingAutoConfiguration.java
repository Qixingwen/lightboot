package cn.nextdev.lightboot.redis.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 框架默认缓存启用配置。
 *
 * <p>在 {@link RedisCacheAutoConfiguration} 之前执行，注册 Spring 缓存注解后处理器。
 * {@link RedisCacheAutoConfiguration} 创建的 {@code RedisCacheManager} 将被自动拾取。
 *
 * <p>{@code @EnableCaching} 是幂等的，与业务方自定义的缓存配置不会冲突。
 * <p>可通过 {@code light-boot.redis.enabled=false} 一并关闭。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "light-boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureBefore(RedisCacheAutoConfiguration.class)
@EnableCaching
public class RedisCachingAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public RedisCachingAutoConfiguration() {
    }

}
