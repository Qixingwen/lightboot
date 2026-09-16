package cn.nextdev.lightboot.redis.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link RedisTemplateAutoConfiguration#lightBootLettuceClientOptionsCustomizer} 的行为：
 * <ul>
 *   <li>始终把 {@code light-boot.redis.connect-timeout-millis} 写入 {@link SocketOptions#getConnectTimeout()}；</li>
 *   <li>自定义值（非默认）正确生效；</li>
 *   <li>默认值 2000ms 正确生效。</li>
 * </ul>
 *
 * <p>测试方式：直接调用 customizer，对真实 {@link ClientOptions.Builder} 施加影响后构建，
 * 再断言其 {@link ClientOptions#getSocketOptions()} 的 {@code getConnectTimeout()}。无需真实 Redis。
 *
 * <p><b>设计背景</b>：Spring Boot 的 {@code LettuceConnectionConfiguration.createClientOptions(...)}
 * 注入并调用容器中所有 {@link LettuceClientOptionsBuilderCustomizer}（已通过 {@code javap} 核对
 * {@code LettuceClientOptionsBuilderCustomizer.customize(io.lettuce.core.ClientOptions$Builder)} 调用），
 * 因此本框架通过该 customizer 接入 {@code connectTimeoutMillis}。本测试即覆盖该 customizer。
 */
class LettuceClientOptionsCustomizerTest {

    private LettuceClientOptionsBuilderCustomizer newCustomizer(RedisProperties properties) {
        return new RedisTemplateAutoConfiguration().lightBootLettuceClientOptionsCustomizer(properties);
    }

    @Test
    void defaultProperties_connectTimeoutIs2000ms() {
        RedisProperties properties = new RedisProperties();
        assertThat(properties.getConnectTimeoutMillis()).isEqualTo(2000L);
    }

    @Test
    void customize_defaultConnectTimeout_applied() {
        RedisProperties properties = new RedisProperties();

        LettuceClientOptionsBuilderCustomizer customizer = newCustomizer(properties);

        ClientOptions.Builder builder = ClientOptions.builder();
        customizer.customize(builder);
        ClientOptions options = builder.build();

        SocketOptions socketOptions = options.getSocketOptions();
        assertThat(socketOptions.getConnectTimeout()).isEqualTo(Duration.ofMillis(2000));
    }

    @Test
    void customize_customConnectTimeout_applied() {
        RedisProperties properties = new RedisProperties();
        properties.setConnectTimeoutMillis(500);

        LettuceClientOptionsBuilderCustomizer customizer = newCustomizer(properties);

        ClientOptions.Builder builder = ClientOptions.builder();
        customizer.customize(builder);
        ClientOptions options = builder.build();

        SocketOptions socketOptions = options.getSocketOptions();
        assertThat(socketOptions.getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
    }
}
