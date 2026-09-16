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
 * {@link MaskingThrowableConverter} 单测：验证异常信息/堆栈经 {@code %maskEx} 脱敏，
 * 以及 {@code mask.enabled=false} / 无容器时的降级与 no-op 行为。
 *
 * <p>静态容器引用需每个用例前后清理，避免污染其他测试。
 */
class MaskingThrowableConverterTest {

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
     * 构造一个不携带异常的事件——{@code super.convert} 返回空串，应原样返回不 NPE。
     */
    private ILoggingEvent eventWithoutThrowable(String message) {
        return event(message, null);
    }

    /**
     * 构造一个携带异常（含指定 message）的事件。
     */
    private ILoggingEvent eventWithThrowable(String throwableMessage) {
        return event("boom", new RuntimeException(throwableMessage));
    }

    private ILoggingEvent event(String message, Throwable throwable) {
        LoggerContext lc = new LoggerContext();
        Logger logger = lc.getLogger("test");
        LoggingEvent event = new LoggingEvent();
        event.setLoggerContextRemoteView(null);
        event.setLoggerName(logger.getName());
        event.setLevel(Level.ERROR);
        event.setMessage(message);
        if (throwable != null) {
            event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(throwable));
        }
        return event;
    }

    /**
     * 无异常的事件返回空串（super.convert 契约），不应触发脱敏或 NPE。
     */
    @Test
    void convert_returnsEmptyForEventWithoutThrowable() {
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        assertThat(converter.convert(eventWithoutThrowable("nothing here"))).isEmpty();
    }

    /**
     * 异常 message 含凭据时被脱敏：{@code password=Hunter123!} → 含 {@code password=******}，不含明文。
     */
    @Test
    void convert_masksCredentialInExceptionMessage() {
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        String rendered = converter.convert(eventWithThrowable("login failed password=Hunter123! secret"));
        assertThat(rendered).contains("password=******");
        assertThat(rendered).doesNotContain("Hunter123");
    }

    /**
     * 异常 message 含手机号时被脱敏。
     */
    @Test
    void convert_masksPhoneInExceptionMessage() {
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        String rendered = converter.convert(eventWithThrowable("contact 13812348888 now"));
        assertThat(rendered).contains("138****8888");
        assertThat(rendered).doesNotContain("13812348888");
    }

    /**
     * enabled=false：异常堆栈原样返回（仍含敏感信息）。
     */
    @Test
    void convert_isNoOpWhenMaskDisabled() {
        ctx.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource(
                        "test",
                        java.util.Collections.singletonMap("light-boot.logging.mask.enabled", "false")));
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        String rendered = converter.convert(eventWithThrowable("password=secret"));
        // 原样返回，敏感信息仍在
        assertThat(rendered).contains("password=secret");
        assertThat(rendered).doesNotContain("password=******");
    }

    /**
     * 无容器（LoggingContextHolder 为 null）：降级到 DefaultLogMasker，仍正常脱敏。
     */
    @Test
    void convert_fallsBackWhenNoContext() {
        LoggingContextHolder.setApplicationContext(null);
        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        String rendered = converter.convert(eventWithThrowable("password=secret"));
        assertThat(rendered).contains("password=******");
    }
}
