package cn.nextdev.lightboot.ratelimit.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流自动配置启动校验回归测试。
 *
 * <p>{@link RateLimitProperties} 的 {@code default-time}/{@code default-count} 必须为正数：
 * {@code default-time} 会作为 {@code RateLimitException} 的 {@code retryAfterSeconds} 传递，
 * 其契约要求 {@code > 0}（负值/0 时 web 层的 {@code Retry-After} 头会静默不写）。
 * 自动配置在启动阶段 fail-fast 拒绝非法配置，而不是等首个请求触发限流时行为异常。
 */
class RateLimitAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RateLimitAutoConfiguration.class));

    /**
     * 默认配置（default-time=1、default-count=100）下上下文正常启动。
     */
    @Test
    void validDefaults_contextStarts() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RateLimitProperties.class);
        });
    }

    /**
     * {@code default-time=0}：启动失败，并给出指出该配置项的错误信息。
     */
    @Test
    void invalidDefaultTime_failsStartup() {
        contextRunner.withPropertyValues("light-boot.ratelimit.default-time=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("default-time");
                });
    }

    /**
     * {@code default-count=-1}：启动失败，并给出指出该配置项的错误信息。
     */
    @Test
    void invalidDefaultCount_failsStartup() {
        contextRunner.withPropertyValues("light-boot.ratelimit.default-count=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("default-count");
                });
    }
}
