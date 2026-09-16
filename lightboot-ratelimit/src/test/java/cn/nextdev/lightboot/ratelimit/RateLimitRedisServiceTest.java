package cn.nextdev.lightboot.ratelimit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RateLimitRedisService 容错分支单测（纯 Mockito，不依赖真实 Redis）：
 * 验证 Lua 脚本返回 nil（execute 返回 null，无法确定计数）时按 failOpen 决策放行/拒绝，
 * 以及两处降级路径（null 回复 / DataAccessException）的限频 WARN 可观测行为——
 * 通过 ListAppender 捕获本类 logger 的事件断言（纯内存，无需真实日志文件）。
 */
class RateLimitRedisServiceTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogCapture() {
        logger = (Logger) LoggerFactory.getLogger(RateLimitRedisService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        logger.detachAppender(appender);
    }

    /**
     * Lua 返回 null 且 failOpen=true（默认）时放行，不抛 NPE。
     */
    @Test
    void allow_failOpensWhenLuaReturnsNull() {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, true);

        assertThat(service.allow("rate:test", 1, 100)).isTrue();
    }

    /**
     * Lua 返回 null 且 failOpen=false 时拒绝，不抛 NPE。
     */
    @Test
    void allow_failClosesWhenConfigured() {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, false);

        assertThat(service.allow("rate:test", 1, 100)).isFalse();
    }

    /**
     * null 降级路径触发限频 WARN，且消息包含实际 failOpen 决策值（failOpen=true）。
     */
    @Test
    void allow_warnsWhenLuaReturnsNull_withFailOpenValue() {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, true);
        service.allow("rate:test", 1, 100);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("failOpen=true");
    }

    /**
     * null 降级路径在 failOpen=false 时同样告警，消息包含 failOpen=false。
     */
    @Test
    void allow_warnsWhenLuaReturnsNull_failClosed() {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, false);
        service.allow("rate:test", 1, 100);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("failOpen=false");
    }

    /**
     * DataAccessException 降级路径触发限频 WARN，且消息包含异常类名（降级原因可感知）。
     */
    @Test
    void allow_warnsOnDataAccessException_withExceptionClassName() {
        RedisTemplate<String, Object> template =
                mockRedisTemplateThrowing(new DataAccessResourceFailureException("connection refused"));

        RateLimitRedisService service = new RateLimitRedisService(template, true);
        service.allow("rate:test", 1, 100);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("DataAccessResourceFailureException");
    }

    /**
     * 限频语义（CAS 门控）：短间隔内连续两次降级只输出 1 条 WARN，避免 Redis 故障期间的日志洪水。
     */
    @Test
    void allow_warnsOnlyOnceForConsecutiveFailuresWithinInterval() {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, true);
        service.allow("rate:test", 1, 100);
        service.allow("rate:test", 1, 100);

        assertThat(appender.list).hasSize(1);
    }

    /**
     * 限频间隔到期后再次降级会重新告警（共 2 条）——证明门控是「限频」而非「只报一次」。
     *
     * <p>真实等待 60s 不现实，通过反射回拨 {@code lastFallbackWarnAt} 模拟间隔已过。
     */
    @Test
    void allow_warnsAgainAfterFallbackIntervalExpires() throws Exception {
        RedisTemplate<String, Object> template = mockRedisTemplateReturningNull();

        RateLimitRedisService service = new RateLimitRedisService(template, true);
        service.allow("rate:test", 1, 100);

        Field field = RateLimitRedisService.class.getDeclaredField("lastFallbackWarnAt");
        field.setAccessible(true);
        AtomicLong lastWarnAt = (AtomicLong) field.get(service);
        lastWarnAt.set(System.currentTimeMillis() - 61_000L);

        service.allow("rate:test", 1, 100);

        assertThat(appender.list).hasSize(2);
    }

    /**
     * 构造 execute 恒返回 null 的模板桩（模拟 Redis nil 回复或反序列化无值）。
     */
    @SuppressWarnings("unchecked")
    private static RedisTemplate<String, Object> mockRedisTemplateReturningNull() {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        when(template.execute(any(), any(), any(), anyList(), any(Object[].class))).thenReturn(null);
        return template;
    }

    /**
     * 构造 execute 恒抛 DataAccessException 的模板桩（模拟 Redis 连接故障）。
     */
    @SuppressWarnings("unchecked")
    private static RedisTemplate<String, Object> mockRedisTemplateThrowing(
            org.springframework.dao.DataAccessException cause) {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        when(template.execute(any(), any(), any(), anyList(), any(Object[].class))).thenThrow(cause);
        return template;
    }
}
