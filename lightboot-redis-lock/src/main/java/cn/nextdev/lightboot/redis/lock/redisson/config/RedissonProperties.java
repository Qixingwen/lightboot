package cn.nextdev.lightboot.redis.lock.redisson.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redisson 扩展配置属性（前缀：{@code light-boot.redis.redisson}）。
 *
 * <p>Redisson 客户端连接复用 Spring Boot 标准的 {@code spring.data.redis.*} 配置，
 * 此属性仅控制 Redisson 装配的开关，不重复定义连接参数。
 *
 * @see cn.nextdev.lightboot.redis.lock.redisson.config.RedissonAutoConfiguration
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "light-boot.redis.redisson")
public class RedissonProperties {

    /**
     * 默认构造方法。
     */
    public RedissonProperties() {
    }

    /**
     * 是否启用 Redisson 客户端与锁的自动装配。
     *
     * <p>关闭后即使引入了本模块，也不会创建 {@code RedissonClient} 与 {@code RedissonDistributedLock}。
     */
    private boolean enabled = true;

    /**
     * 是否装配 {@code RedissonDistributedLock}。
     *
     * <p>设为 {@code false} 时仅创建 {@code RedissonClient}（供业务直接使用 Redisson 全部能力），不装配锁封装。
     */
    private boolean lockEnabled = true;

}
