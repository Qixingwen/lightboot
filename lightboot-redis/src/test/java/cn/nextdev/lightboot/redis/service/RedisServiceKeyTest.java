package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisService 通用操作族单测：验证 delete（单个/批量）/expire（keyDef 超时/自定义超时）/
 * expireAt/increment(delta)/decrement/hasKey 的委托方向、完整 key = 全局前缀 + 业务前缀 + 后缀、
 * 超时与步长等参数如实传递、返回值如实透传。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceKeyTest {

    /**
     * 测试用 KeyDefinition，前缀 auth:sms:code:，超时 10 分钟。
     */
    private static final RedisKeyDefinition SMS_KEY = new RedisKeyDefinition() {
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

    @Captor
    private ArgumentCaptor<Collection<String>> keysCaptor;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * delete 透传 key 是否被删除（key 存在时为 true）。
     */
    @Test
    void delete_returnsTrueWhenKeyRemoved() {
        when(redisTemplate.delete("light-boot:auth:sms:code:13800138000")).thenReturn(true);

        assertThat(redisService.delete(SMS_KEY, "13800138000")).isTrue();
    }

    /**
     * delete 在 key 不存在时透传 false。
     */
    @Test
    void delete_returnsFalseWhenKeyAbsent() {
        when(redisTemplate.delete("light-boot:auth:sms:code:missing")).thenReturn(false);

        assertThat(redisService.delete(SMS_KEY, "missing")).isFalse();
    }

    /**
     * 批量 delete 将每个后缀分别拼接全局前缀后整体传给 RedisTemplate，并透传删除数量。
     */
    @Test
    void deleteCollection_buildsFullKeysAndReturnsDeletedCount() {
        when(redisTemplate.delete(anyCollection())).thenReturn(2L);

        Long deleted = redisService.delete(SMS_KEY, List.of("k1", "k2"));

        assertThat(deleted).isEqualTo(2L);
        verify(redisTemplate).delete(keysCaptor.capture());
        assertThat(keysCaptor.getValue())
                .containsExactly("light-boot:auth:sms:code:k1", "light-boot:auth:sms:code:k2");
    }

    /**
     * expire 使用 KeyDefinition 的超时（分钟）转换为 Duration 传递。
     */
    @Test
    void expire_appliesKeyDefinitionTimeout() {
        when(redisTemplate.expire("light-boot:auth:sms:code:13800138000", Duration.ofMinutes(10)))
                .thenReturn(true);

        assertThat(redisService.expire(SMS_KEY, "13800138000")).isTrue();
    }

    /**
     * expire 支持自定义超时时长与单位，转换为 Duration 传递。
     */
    @Test
    void expire_acceptsCustomTimeoutAndUnit() {
        when(redisTemplate.expire("light-boot:auth:sms:code:13800138000", Duration.ofSeconds(30)))
                .thenReturn(true);

        assertThat(redisService.expire(SMS_KEY, "13800138000", 30, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * expireAt 将绝对过期时间原样传递给 RedisTemplate。
     */
    @Test
    void expireAt_passesAbsoluteInstant() {
        Instant expireAt = Instant.parse("2030-01-01T00:00:00Z");
        when(redisTemplate.expireAt("light-boot:auth:sms:code:13800138000", expireAt)).thenReturn(true);

        assertThat(redisService.expireAt(SMS_KEY, "13800138000", expireAt)).isTrue();
    }

    /**
     * increment(delta) 将步长如实传递给 opsForValue.increment 并透传自增后的值。
     */
    @Test
    void incrementWithDelta_passesDelta() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("light-boot:auth:sms:code:counter", 5L)).thenReturn(6L);

        assertThat(redisService.increment(SMS_KEY, "counter", 5L)).isEqualTo(6L);
    }

    /**
     * decrement 委托给 opsForValue.decrement 执行自减并透传结果。
     */
    @Test
    void decrement_delegatesToOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement("light-boot:auth:sms:code:counter")).thenReturn(2L);

        assertThat(redisService.decrement(SMS_KEY, "counter")).isEqualTo(2L);
    }

    /**
     * hasKey 判断 key 是否存在，透传布尔结果。
     */
    @Test
    void hasKey_returnsKeyExistence() {
        when(redisTemplate.hasKey("light-boot:auth:sms:code:13800138000")).thenReturn(true);

        assertThat(redisService.hasKey(SMS_KEY, "13800138000")).isTrue();
    }
}
