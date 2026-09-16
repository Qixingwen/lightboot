package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Iterator;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisService 指标埋点单测：验证 RedisMetricsRecorder 被正确调用，以及在记录器缺失时 null 安全。
 *
 * <p>覆盖：
 * <ul>
 *   <li>读写成功时以 success=true 记录对应操作名</li>
 *   <li>写失败（抛异常）时以 success=false 记录，异常仍向上抛</li>
 *   <li>记录器 provider 为 null（无 actuator）时不抛异常、不记录</li>
 *   <li>provider 返回 null（记录器 bean 未装配）时同样 null 安全</li>
 * </ul>
 *
 * <p>使用真实 {@link ObjectProvider} 实现（而非 mock），避免对接口默认方法的 stubbing 问题。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceMetricsTest {

    private static final RedisKeyDefinition KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "auth:sms:code:";
        }

        @Override
        public Long getTimeout() {
            return 10L;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private RedisMetricsRecorder recorder;

    private RedisService redisService;

    /**
     * 构造一个固定返回给定记录器的 {@link ObjectProvider}（避免 mock 接口默认方法）。
     *
     * @param recorder 记录器实例，可为 {@code null}（模拟 bean 未装配）
     */
    private static ObjectProvider<RedisMetricsRecorder> providerOf(RedisMetricsRecorder recorder) {
        return new ObjectProvider<RedisMetricsRecorder>() {
            @Override
            public RedisMetricsRecorder getObject(Object... args) {
                return recorder;
            }

            @Override
            public RedisMetricsRecorder getIfAvailable() {
                return recorder;
            }

            @Override
            public RedisMetricsRecorder getIfAvailable(Supplier<RedisMetricsRecorder> defaultSupplier) {
                return recorder != null ? recorder : defaultSupplier.get();
            }

            @Override
            public Iterator<RedisMetricsRecorder> iterator() {
                return Stream.of(recorder).iterator();
            }

            @Override
            public Stream<RedisMetricsRecorder> stream() {
                return Stream.of(recorder);
            }

            @Override
            public Stream<RedisMetricsRecorder> orderedStream() {
                return Stream.of(recorder);
            }
        };
    }

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:", providerOf(recorder));
    }

    /**
     * getString 成功时以 operation=get、success=true 记录耗时。
     */
    @Test
    void getString_recordsSuccessOnGet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn("v");

        assertThat(redisService.getString(KEY, "k")).isEqualTo("v");

        verify(recorder).record(eq("get"), anyLong(), eq(true));
    }

    /**
     * setObject 成功时以 operation=set、success=true 记录。
     */
    @Test
    void setObject_recordsSuccessOnSet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setObject(KEY, "k", "v");

        verify(recorder).record(eq("set"), anyLong(), eq(true));
    }

    /**
     * increment 成功时以 operation=incr 记录。
     */
    @Test
    void increment_recordsAsIncr() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("light-boot:auth:sms:code:c")).thenReturn(2L);

        assertThat(redisService.increment(KEY, "c")).isEqualTo(2L);

        verify(recorder).record(eq("incr"), anyLong(), eq(true));
    }

    /**
     * delete 成功时以 operation=delete 记录。
     */
    @Test
    void delete_recordsAsDelete() {
        when(redisTemplate.delete("light-boot:auth:sms:code:k")).thenReturn(true);

        redisService.delete(KEY, "k");

        verify(recorder).record(eq("delete"), anyLong(), eq(true));
    }

    /**
     * 操作抛异常时以 success=false 记录，且异常向上抛（不被吞掉）。
     */
    @Test
    void setObject_recordsFailureAndRethrowsOnException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).set("light-boot:auth:sms:code:k", "v", Duration.ofMinutes(10));

        assertThatThrownBy(() -> redisService.setObject(KEY, "k", "v"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("redis down");

        verify(recorder).record(eq("set"), anyLong(), eq(false));
    }

    /**
     * 当 metricsRecorderProvider 为 null（无 actuator，使用 2 参构造）时，recordOp 无操作、不抛异常。
     */
    @Test
    void recordOp_isNullSafeWhenProviderAbsent() {
        RedisService noMetrics = new RedisService(redisTemplate, "light-boot:");  // 2 参构造 → provider 为 null
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn("v");

        assertThat(noMetrics.getString(KEY, "k")).isEqualTo("v");

        verify(recorder, never()).record(anyString(), anyLong(), anyBoolean());
    }

    /**
     * 当 provider 返回 null（actuator 缺失 MeterRegistry，记录器 bean 未装配）时，recordOp 无操作。
     */
    @Test
    void recordOp_isNullSafeWhenRecorderBeanAbsent() {
        RedisService emptyProvider = new RedisService(redisTemplate, "light-boot:", providerOf(null));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn("v");

        assertThat(emptyProvider.getString(KEY, "k")).isEqualTo("v");

        verify(recorder, never()).record(anyString(), anyLong(), anyBoolean());
    }
}
