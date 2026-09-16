package cn.nextdev.lightboot.redis.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存配置属性（前缀：{@code light-boot.cache.redis}）。
 *
 * <p>配置示例：
 * <pre>{@code
 * light-boot:
 *   cache:
 *     redis:
 *       key-prefix: "app:"
 *       time-to-live: 3600
 *       cache-null-values: true
 *       use-key-prefix: true
 *       caches:
 *         users:
 *           time-to-live: 1800
 *         products:
 *           time-to-live: 7200
 * }</pre>
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "light-boot.cache.redis")
public class RedisCacheProperties {

    /**
     * 默认构造方法。
     */
    public RedisCacheProperties() {
    }

    /**
     * 缓存 Key 前缀，用于共享 Redis 实例中的命名空间隔离。
     * 例如 {@code "app:"} 会生成 {@code "app:users::1"} 形式的 Key。
     */
    private String keyPrefix = "app:";

    /**
     * 缓存条目默认过期时间（秒），可被 {@link #caches} 中的独立配置覆盖。
     */
    private Integer timeToLive = 3600;

    /**
     * 是否缓存 {@code null} 值。开启后可防止缓存穿透，但会增加内存占用。
     */
    private boolean cacheNullValues = false;

    /**
     * 是否启用 Key 前缀，建议在共享 Redis 或多应用场景下启用。
     */
    private boolean useKeyPrefix = true;

    /**
     * 按缓存名独立配置，每个缓存可单独覆盖默认 TTL。
     */
    private Map<String, CacheSpecificProperties> caches = new HashMap<>();

    /**
     * 返回缓存配置摘要。
     */
    @Override
    public String toString() {
        return "RedisCacheProperties{" +
                "keyPrefix='" + keyPrefix + '\'' +
                ", timeToLive=" + timeToLive +
                ", cacheNullValues=" + cacheNullValues +
                ", useKeyPrefix=" + useKeyPrefix +
                ", caches=" + caches.keySet() +
                '}';
    }

    /**
     * 单个缓存的独立配置属性。
     */
    @Setter
    @Getter
    public static class CacheSpecificProperties {

        /**
         * 默认构造方法。
         */
        public CacheSpecificProperties() {
        }

        /**
         * 该缓存的过期时间（秒），覆盖默认 {@link RedisCacheProperties#timeToLive}。
         */
        private Integer timeToLive;
    }

}
