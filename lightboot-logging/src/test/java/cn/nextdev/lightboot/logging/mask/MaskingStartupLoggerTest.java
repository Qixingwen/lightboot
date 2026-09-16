package cn.nextdev.lightboot.logging.mask;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.StaticApplicationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MaskingStartupLogger} 单测：验证启动时恰好发一条 WARN（含接线片段），且去重。
 */
class MaskingStartupLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger maskLogger;
    private StaticApplicationContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new StaticApplicationContext();
        ctx.refresh();
        maskLogger = (Logger) LoggerFactory.getLogger("light-boot.logging.mask");
        appender = new ListAppender<>();
        appender.start();
        maskLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        if (maskLogger != null && appender != null) {
            maskLogger.detachAppender(appender);
            appender.stop();
        }
    }

    private ApplicationReadyEvent readyEvent() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], ctx, Duration.ZERO);
    }

    @Test
    void logsWarningOnceOnReadyEvent() {
        MaskingStartupLogger listener = new MaskingStartupLogger();
        listener.onApplicationEvent(readyEvent());
        // 重复触发不应再次告警
        listener.onApplicationEvent(readyEvent());

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);
        String msg = warns.get(0).getFormattedMessage();
        assertThat(msg).contains("maskMsg");
        assertThat(msg).contains("maskEx");
        assertThat(msg).contains("MaskingConverter");
        assertThat(msg).contains("MaskingThrowableConverter");
    }

    @Test
    void noWarningWhenMaskingWired() {
        ctx.getEnvironment().getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource(
                        "test", java.util.Map.of("light-boot.logging.mask.wired", "true")));

        MaskingStartupLogger listener = new MaskingStartupLogger();
        listener.onApplicationEvent(readyEvent());

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).isEmpty();
    }

    @Test
    void warnsWhenMaskingNotWired() {
        ctx.getEnvironment().getPropertySources()
                .addFirst(new org.springframework.core.env.MapPropertySource(
                        "test", java.util.Map.of("light-boot.logging.mask.wired", "false")));

        MaskingStartupLogger listener = new MaskingStartupLogger();
        listener.onApplicationEvent(readyEvent());

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .toList();
        assertThat(warns).hasSize(1);
    }
}
