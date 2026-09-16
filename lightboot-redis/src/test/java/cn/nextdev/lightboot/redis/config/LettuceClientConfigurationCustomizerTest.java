package cn.nextdev.lightboot.redis.config;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link RedisTemplateAutoConfiguration#lightBootLettuceClientConfigurationCustomizer} 的行为：
 * <ul>
 *   <li>始终把 {@code light-boot.redis.command-timeout-millis} 写入命令超时；</li>
 *   <li>当启用连接池且构建器为「带池」构建器时，把有界池配置写入；</li>
 *   <li>当未启用池或构建器为非池构建器时，不报错、不写入池配置。</li>
 * </ul>
 *
 * <p>测试方式：直接调用 customizer，对真实 {@link LettuceClientConfiguration} 构建器施加影响后构建，
 * 再断言其 getter（{@code getCommandTimeout()} / {@code instanceof LettucePoolingClientConfiguration} 与
 * {@code getPoolConfig()}）。无需真实 Redis 连接。
 *
 * <p><b>设计背景</b>：Spring Boot 的 {@code LettuceConnectionConfiguration} 不消费
 * {@link LettuceClientConfiguration} Bean，而是调用容器中所有 {@link LettuceClientConfigurationBuilderCustomizer}，
 * 因此本框架通过 customizer 接入；本测试即覆盖该 customizer。
 */
class LettuceClientConfigurationCustomizerTest {

    private LettuceClientConfigurationBuilderCustomizer newCustomizer(RedisProperties properties) {
        return new RedisTemplateAutoConfiguration().lightBootLettuceClientConfigurationCustomizer(properties);
    }

    @Test
    void defaultProperties_commandTimeoutIs3000ms() {
        RedisProperties properties = new RedisProperties();
        assertThat(properties.getCommandTimeoutMillis()).isEqualTo(3000L);
        assertThat(properties.getConnectTimeoutMillis()).isEqualTo(2000L);
        assertThat(properties.getPool().isEnabled()).isTrue();
        assertThat(properties.getPool().getMaxTotal()).isEqualTo(16);
        assertThat(properties.getPool().getMaxIdle()).isEqualTo(8);
        assertThat(properties.getPool().getMinIdle()).isEqualTo(2);
        assertThat(properties.getPool().getMaxWaitMillis()).isEqualTo(2000L);
    }

    @Test
    void customize_poolingBuilder_appliesCommandTimeoutAndBoundedPool() {
        RedisProperties properties = new RedisProperties();
        properties.getPool().setEnabled(true);
        properties.getPool().setMaxTotal(32);
        properties.getPool().setMaxIdle(12);
        properties.getPool().setMinIdle(4);
        properties.getPool().setMaxWaitMillis(1500);
        properties.setCommandTimeoutMillis(5000);

        LettuceClientConfigurationBuilderCustomizer customizer = newCustomizer(properties);

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();

        customizer.customize(builder);

        LettucePoolingClientConfiguration config = builder.build();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(5000));
        assertThat(config.getClientName()).contains("light-boot-redis");
        assertThat(config).isInstanceOf(LettucePoolingClientConfiguration.class);

        GenericObjectPoolConfig<?> poolConfig = config.getPoolConfig();
        assertThat(poolConfig.getMaxTotal()).isEqualTo(32);
        assertThat(poolConfig.getMaxIdle()).isEqualTo(12);
        assertThat(poolConfig.getMinIdle()).isEqualTo(4);
        // GenericObjectPoolConfig 持有 maxWait 为 Duration，取回比较时折算毫秒
        assertThat(poolConfig.getMaxWaitDuration()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void customize_poolingBuilder_defaultPoolValuesApplied() {
        RedisProperties properties = new RedisProperties();

        LettuceClientConfigurationBuilderCustomizer customizer = newCustomizer(properties);

        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        customizer.customize(builder);

        LettucePoolingClientConfiguration config = builder.build();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(3000));
        assertThat(config).isInstanceOf(LettucePoolingClientConfiguration.class);

        GenericObjectPoolConfig<?> poolConfig = config.getPoolConfig();
        assertThat(poolConfig.getMaxTotal()).isEqualTo(16);
        assertThat(poolConfig.getMaxIdle()).isEqualTo(8);
        assertThat(poolConfig.getMinIdle()).isEqualTo(2);
        assertThat(poolConfig.getMaxWaitDuration()).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void customize_poolDisabled_doesNotApplyPool_andStillSetsCommandTimeout() {
        RedisProperties properties = new RedisProperties();
        properties.getPool().setEnabled(false);
        properties.setCommandTimeoutMillis(7000);

        LettuceClientConfigurationBuilderCustomizer customizer = newCustomizer(properties);

        // 即使传入的是带池构建器，pool.enabled=false 时也不应写入池配置
        LettucePoolingClientConfiguration.LettucePoolingClientConfigurationBuilder builder =
                LettucePoolingClientConfiguration.builder();
        customizer.customize(builder);

        LettuceClientConfiguration config = builder.build();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(7000));
        // 带池构建器仍构建为 Pooling 类型，但池配置保持默认（未被本框架覆盖）
        assertThat(config).isInstanceOf(LettucePoolingClientConfiguration.class);
    }

    @Test
    void customize_nonPoolingBuilder_appliesCommandTimeoutWithoutError() {
        RedisProperties properties = new RedisProperties();
        properties.setCommandTimeoutMillis(9000);

        LettuceClientConfigurationBuilderCustomizer customizer = newCustomizer(properties);

        // Boot 在未启用池时传入的是非池构建器；customizer 不应因无法写入池配置而报错
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        customizer.customize(builder);

        LettuceClientConfiguration config = builder.build();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(9000));
        assertThat(config).isNotInstanceOf(LettucePoolingClientConfiguration.class);
    }
}
