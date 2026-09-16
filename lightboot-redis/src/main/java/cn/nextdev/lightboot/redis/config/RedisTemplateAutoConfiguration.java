package cn.nextdev.lightboot.redis.config;

import cn.nextdev.lightboot.redis.serializer.RedisJsonSerializerFactory;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulConnection;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * RedisTemplate 自动配置。
 *
 * <p>在 Spring Boot 内置的 {@code DataRedisAutoConfiguration} 之前执行，使用统一的序列化策略：
 * <ul>
 *   <li>Key / HashKey — {@link StringRedisSerializer}（便于 Redis CLI 查看）</li>
 *   <li>Value / HashValue — {@link GenericJacksonJsonRedisSerializer}（Jackson 3，保留 Java 类型信息）</li>
 * </ul>
 *
 * <p>可通过 {@code light-boot.redis.enabled=false} 关闭。
 */
@AutoConfiguration
@ConditionalOnClass({RedisTemplate.class, RedisConnectionFactory.class})
@ConditionalOnProperty(prefix = "light-boot.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@AutoConfigureBefore(org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration.class)
@EnableConfigurationProperties(RedisProperties.class)
@Slf4j
public class RedisTemplateAutoConfiguration {

    private static final RedisJsonSerializerFactory SERIALIZER_FACTORY = new RedisJsonSerializerFactory();

    /**
     * 默认构造方法。
     */
    public RedisTemplateAutoConfiguration() {
    }

    /**
     * 自定义 {@link RedisTemplate}，使用 {@code @Primary} 确保注入优先级。
     *
     * @param connectionFactory Redis 连接工厂
     * @param properties        Redis 扩展配置属性
     * @return 配置完成的 {@link RedisTemplate} 实例
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(RedisTemplate.class)
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory, RedisProperties properties) {
        log.info("Initializing RedisTemplate with unified JSON serialization");

        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer(StandardCharsets.UTF_8);
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);

        GenericJacksonJsonRedisSerializer jsonSerializer = SERIALIZER_FACTORY.create(properties);
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        redisTemplate.afterPropertiesSet();

        log.info("RedisTemplate initialized successfully");
        return redisTemplate;
    }

    /**
     * 自定义 Spring Boot 创建的 Lettuce 客户端配置：注入命令超时与（启用时）有界连接池。
     *
     * <p><b>接入点说明</b>：Spring Boot 的 {@code LettuceConnectionConfiguration}
     * 并不会消费容器中已存在的 {@link LettuceClientConfiguration} Bean，而是在内部构建连接工厂时调用所有
     * {@link LettuceClientConfigurationBuilderCustomizer}。因此本框架通过该 Customizer 作为接入点：
     * <ul>
     *   <li>始终设置 {@code commandTimeout}（覆盖 {@code spring.data.redis.timeout}）——Customizer 在
     *       Boot 的 {@code applyProperties} 之后执行，可生效；</li>
     *   <li>设置 {@code clientName}；</li>
     *   <li>当 {@code pool.enabled=true} 且 Boot 传入的是「带池」构建器
     *       （{@link LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder}）时，
     *       通过 {@code poolConfig(...)} 应用有界连接池配置。</li>
     * </ul>
     *
     * <p>{@code connectTimeoutMillis} 不经此 customizer 接入，而由
     * {@link #lightBootLettuceClientOptionsCustomizer(RedisProperties)} 通过 {@link SocketOptions} 应用。
     *
     * @param properties Redis 扩展配置属性
     * @return Lettuce 客户端配置构建器自定义器
     */
    @Bean
    @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
    @ConditionalOnMissingBean(LettuceClientConfigurationBuilderCustomizer.class)
    public LettuceClientConfigurationBuilderCustomizer lightBootLettuceClientConfigurationCustomizer(RedisProperties properties) {
        Duration commandTimeout = Duration.ofMillis(properties.getCommandTimeoutMillis());
        RedisProperties.PoolProperties pool = properties.getPool();

        return builder -> {
            builder.commandTimeout(commandTimeout);
            builder.clientName("light-boot-redis");

            if (pool.isEnabled() && builder instanceof LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder) {
                GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig = new GenericObjectPoolConfig<>();
                poolConfig.setMaxTotal(pool.getMaxTotal());
                poolConfig.setMaxIdle(pool.getMaxIdle());
                poolConfig.setMinIdle(pool.getMinIdle());
                poolConfig.setMaxWait(Duration.ofMillis(pool.getMaxWaitMillis()));
                ((LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder) builder).poolConfig(poolConfig);
                log.info("Applied bounded Lettuce pool: maxTotal={}, maxIdle={}, minIdle={}, maxWaitMillis={}",
                        pool.getMaxTotal(), pool.getMaxIdle(), pool.getMinIdle(), pool.getMaxWaitMillis());
            }
            log.info("Applied Lettuce commandTimeout={}ms", properties.getCommandTimeoutMillis());
        };
    }

    /**
     * 自定义 Spring Boot 创建的 Lettuce {@link io.lettuce.core.ClientOptions}：通过 {@link SocketOptions}
     * 把 {@code light-boot.redis.connect-timeout-millis} 应用到连接超时。
     *
     * <p><b>接入点说明</b>：Spring Boot 的 {@code LettuceConnectionConfiguration}
     * 在 {@code createClientOptions(...)} 中注入并调用所有 {@link LettuceClientOptionsBuilderCustomizer}，
     * 故本 customizer 作为 {@code connectTimeoutMillis} 的接入点。
     *
     * <p>{@link io.lettuce.core.ClientOptions.Builder} 暴露 {@code socketOptions(SocketOptions)}，
     * {@link SocketOptions.Builder} 暴露 {@code connectTimeout(Duration)}。
     *
     * <p>注意：连接超时经 {@code ClientOptions} 设置，与命令超时（{@link LettuceClientConfiguration}）
     * 属两条独立路径，二者互不冲突。
     *
     * @param properties Redis 扩展配置属性
     * @return Lettuce 客户端选项构建器自定义器
     */
    @Bean
    @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
    @ConditionalOnMissingBean(LettuceClientOptionsBuilderCustomizer.class)
    public LettuceClientOptionsBuilderCustomizer lightBootLettuceClientOptionsCustomizer(RedisProperties properties) {
        Duration connectTimeout = Duration.ofMillis(properties.getConnectTimeoutMillis());
        return clientOptionsBuilder -> clientOptionsBuilder.socketOptions(
                SocketOptions.builder()
                        .connectTimeout(connectTimeout)
                        .build());
    }

}
