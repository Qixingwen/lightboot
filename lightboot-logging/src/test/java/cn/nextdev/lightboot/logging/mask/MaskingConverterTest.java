package cn.nextdev.lightboot.logging.mask;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaskingConverter} 单测：重点验证 {@code light-boot.logging.mask.enabled=false} 时
 * 转换器成为 no-op（原样返回），以及默认（true）/无容器时的脱敏行为。
 *
 * <p>本测试需通过 {@link LoggingContextHolder} 注入静态容器引用，故每个用例前后需清理，
 * 避免静态状态污染其他测试。
 */
class MaskingConverterTest {

    private StaticApplicationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new StaticApplicationContext();
        ctx.refresh();
        LoggingContextHolder.setApplicationContext(ctx);
    }

    @AfterEach
    void tearDown() {
        LoggingContextHolder.setApplicationContext(null);
    }

    /**
     * 构造一个携带给定格式化消息的 ILoggingEvent。
     */
    private ILoggingEvent eventWithMessage(String message) {
        LoggerContext lc = new LoggerContext();
        Logger logger = lc.getLogger("test");
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContextRemoteView(null);
        event.setLoggerName(logger.getName());
        event.setLevel(Level.INFO);
        event.setMessage(message);
        return event;
    }

    /**
     * enabled=false：含敏感信息的消息原样返回（不脱敏）。
     */
    @Test
    void convert_isNoOpWhenMaskDisabled() {
        ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource(
                        "test",
                        java.util.Collections.singletonMap("light-boot.logging.mask.enabled", "false")));
        MaskingConverter converter = new MaskingConverter();
        String sensitive = "联系 13812348888 password=secret";
        assertThat(converter.convert(eventWithMessage(sensitive)))
                .isEqualTo(sensitive);
    }

    /**
     * 默认（enabled 未设置，默认 true）：手机号与凭据均被脱敏。
     */
    @Test
    void convert_masksByDefault() {
        MaskingConverter converter = new MaskingConverter();
        String masked = converter.convert(eventWithMessage("联系 13812348888"));
        assertThat(masked).contains("138****8888");
    }

    /**
     * enabled=true（显式）：手机号被脱敏。
     */
    @Test
    void convert_masksWhenExplicitlyEnabled() {
        ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource(
                        "test",
                        java.util.Collections.singletonMap("light-boot.logging.mask.enabled", "true")));
        MaskingConverter converter = new MaskingConverter();
        String masked = converter.convert(eventWithMessage("联系 13812348888"));
        assertThat(masked).contains("138****8888");
    }

    /**
     * 无容器（LoggingContextHolder 为 null）：降级到 DefaultLogMasker，仍正常脱敏。
     */
    @Test
    void convert_fallsBackWhenNoContext() {
        LoggingContextHolder.setApplicationContext(null);
        MaskingConverter converter = new MaskingConverter();
        String masked = converter.convert(eventWithMessage("联系 13812348888"));
        assertThat(masked).contains("138****8888");
    }
}
