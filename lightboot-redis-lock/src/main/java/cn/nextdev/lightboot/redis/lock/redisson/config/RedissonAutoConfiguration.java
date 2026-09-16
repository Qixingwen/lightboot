package cn.nextdev.lightboot.redis.lock.redisson.config;

import cn.nextdev.lightboot.redis.config.RedisTemplateAutoConfiguration;
import cn.nextdev.lightboot.redis.lock.redisson.RedissonDistributedLock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * Redisson 自动配置。
 *
 * <p>从 Spring Boot 标准的 {@code spring.data.redis.*} 配置构造 Redisson 客户端，
 * 支持单机、哨兵、集群三种连接模式。连接池等参数沿用 Redisson 默认值。
 *
 * <p>装配内容：
 * <ul>
 *   <li>{@link RedissonClient} — 仅当容器中不存在时创建，用户可自定义覆盖</li>
 *   <li>{@link RedissonDistributedLock} — 锁封装，可通过 {@code light-boot.redis.redisson.lock-enabled=false} 关闭</li>
 * </ul>
 *
 * <p>可通过 {@code light-boot.redis.redisson.enabled=false} 关闭整个模块。
 *
 * @see RedissonProperties
 */
@AutoConfiguration
@AutoConfigureAfter(RedisTemplateAutoConfiguration.class)
@ConditionalOnClass(Redisson.class)
@ConditionalOnProperty(prefix = "light-boot.redis.redisson", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({RedissonProperties.class, DataRedisProperties.class})
public class RedissonAutoConfiguration {

    /**
     * 默认构造方法。
     */
    public RedissonAutoConfiguration() {
    }

    /**
     * 创建 Redisson 客户端，复用 {@code spring.data.redis} 配置。
     *
     * @param redisProperties Spring Boot 绑定的 {@code spring.data.redis} 配置
     * @return {@link RedissonClient} 实例
     */
    @Bean
    @ConditionalOnMissingBean(RedissonClient.class)
    public RedissonClient redissonClient(DataRedisProperties redisProperties) {
        Config config = new Config();
        // 用户名与密码在 Config 级别设置
        applyCredentials(config, redisProperties);

        // 集群模式优先
        if (redisProperties.getCluster() != null
                && redisProperties.getCluster().getNodes() != null
                && !redisProperties.getCluster().getNodes().isEmpty()) {
            ClusterServersConfig cluster = config.useClusterServers();
            for (String node : redisProperties.getCluster().getNodes()) {
                cluster.addNodeAddress(toRedissonAddress(node, redisProperties.getSsl().isEnabled()));
            }
        } else if (redisProperties.getSentinel() != null
                && StringUtils.hasText(redisProperties.getSentinel().getMaster())) {
            // 哨兵模式
            SentinelServersConfig sentinel = config.useSentinelServers();
            sentinel.setMasterName(redisProperties.getSentinel().getMaster());
            assert redisProperties.getSentinel().getNodes() != null;
            for (String node : redisProperties.getSentinel().getNodes()) {
                sentinel.addSentinelAddress(toRedissonAddress(node, redisProperties.getSsl().isEnabled()));
            }
            if (redisProperties.getDatabase() != 0) {
                sentinel.setDatabase(redisProperties.getDatabase());
            }
        } else {
            // 单机模式
            SingleServerConfig single = config.useSingleServer();
            single.setAddress(toRedissonAddress(redisProperties.getHost(), redisProperties.getPort(), redisProperties.getSsl().isEnabled()));
            single.setDatabase(redisProperties.getDatabase());
        }

        return Redisson.create(config);
    }

    /**
     * 装配 Redisson 锁封装。
     *
     * @param redissonClient  Redisson 客户端
     * @param redisProperties 框架 Redis 属性，提供全局 Key 前缀
     * @return {@link RedissonDistributedLock} 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "light-boot.redis.redisson", name = "lock-enabled", havingValue = "true", matchIfMissing = true)
    public RedissonDistributedLock redissonDistributedLock(RedissonClient redissonClient, cn.nextdev.lightboot.redis.config.RedisProperties redisProperties) {
        return new RedissonDistributedLock(redissonClient, redisProperties.getKeyPrefix());
    }

    // ---------- 地址与凭证辅助 ----------

    /**
     * 将 {@code host:port} 形式的地址转换为 Redisson 的 {@code redis://host:port} 或 {@code rediss://host:port}（TLS）格式。
     *
     * <p>包级可见以便单测直接覆盖地址构建逻辑（不触发真实 Redisson 连接）。
     *
     * @param host 主机
     * @param port 端口
     * @param ssl  是否启用 TLS（对应 {@code spring.data.redis.ssl}），为 {@code true} 时使用 {@code rediss://}
     * @return Redisson 格式地址：{@code redis://host:port} 或 {@code rediss://host:port}
     */
    static String toRedissonAddress(String host, int port, boolean ssl) {
        return (ssl ? "rediss://" : "redis://") + host + ":" + port;
    }

    /**
     * 将集群/哨兵节点地址（{@code host:port}）转换为 Redisson 格式。
     *
     * <p>若节点已显式带 scheme（{@code redis://} / {@code rediss://}）则原样保留（显式优先）；
     * 否则按 {@code ssl} 决定补 {@code redis://} 还是 {@code rediss://}，与单机模式行为一致。
     * 包级可见以便单测直接覆盖。
     *
     * @param node 节点地址（{@code host:port} 或已带 scheme），前后空白会被裁剪
     * @param ssl  是否启用 TLS（仅在节点未显式带 scheme 时生效）
     * @return Redisson 格式节点地址
     */
    static String toRedissonAddress(String node, boolean ssl) {
        String trimmed = node.trim();
        if (trimmed.startsWith("redis://") || trimmed.startsWith("rediss://")) {
            return trimmed;
        }
        return (ssl ? "rediss://" : "redis://") + trimmed;
    }

    /**
     * 在 {@link Config} 级别应用用户名与密码（对所有连接模式生效）。
     *
     * @param config          Redisson 配置
     * @param redisProperties Spring Boot 的 {@code spring.data.redis} 配置（提供用户名与密码）
     */
    private void applyCredentials(Config config, DataRedisProperties redisProperties) {
        if (StringUtils.hasText(redisProperties.getUsername())) {
            config.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            config.setPassword(redisProperties.getPassword());
        }
    }

}
