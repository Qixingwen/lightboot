package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisService 单测：验证全局前缀拼接、set/get 委托、String 类型转换。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceTest {

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

    /**
     * 测试用 KeyDefinition，永不过期（timeout 为 null）。
     */
    private static final RedisKeyDefinition NEVER_EXPIRE_KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "user:detail:";
        }

        @Override
        public Long getTimeout() {
            return null;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * setString 使用 KeyDefinition 的超时，并拼接全局前缀组成完整 key。
     */
    @Test
    void setString_appliesKeyDefinitionTimeoutAndGlobalPrefix() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setString(SMS_KEY, "13800138000", "123456");

        // 验证完整 key = 全局前缀 + 业务前缀 + 后缀
        verify(valueOps).set(eq("light-boot:auth:sms:code:13800138000"), eq("123456"),
                eq(Duration.ofMinutes(10)));
    }

    /**
     * setString 支持自定义超时时长与单位。
     */
    @Test
    void setString_acceptsCustomTimeoutAndUnit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setString(SMS_KEY, "13800138000", "123456", 5, TimeUnit.SECONDS);

        verify(valueOps).set(eq("light-boot:auth:sms:code:13800138000"), eq("123456"),
                eq(Duration.ofSeconds(5)));
    }

    /**
     * getString 返回 Redis 中的 String 值。
     */
    @Test
    void getString_returnsStringValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:13800138000")).thenReturn("123456");

        assertThat(redisService.getString(SMS_KEY, "13800138000")).isEqualTo("123456");
    }

    /**
     * getString 在值为非 String 对象时调用 toString 转换。
     */
    @Test
    void getString_callsToStringForNonStringValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn(12345L);

        assertThat(redisService.getString(SMS_KEY, "k")).isEqualTo("12345");
    }

    /**
     * getString 在 key 不存在时返回 null。
     */
    @Test
    void getString_returnsNullWhenKeyAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        assertThat(redisService.getString(SMS_KEY, "missing")).isNull();
    }

    /**
     * globalPrefix 为 null 时按空串处理，key 不带全局前缀。
     */
    @Test
    void globalPrefix_treatedAsEmptyWhenNull() {
        RedisService noPrefix = new RedisService(redisTemplate, null);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        noPrefix.setString(SMS_KEY, "k", "v");

        verify(valueOps).set(eq("auth:sms:code:k"), eq("v"), eq(Duration.ofMinutes(10)));
    }

    /**
     * increment 委托给 opsForValue 执行自增。
     */
    @Test
    void increment_delegatesToOpsForValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("light-boot:auth:sms:code:counter")).thenReturn(3L);

        assertThat(redisService.increment(SMS_KEY, "counter")).isEqualTo(3L);
        verify(valueOps, times(1)).increment("light-boot:auth:sms:code:counter");
    }

    /**
     * getObject(Class) 在缓存值类型不匹配时不抛 ClassCastException，返回 null（缓存未命中语义）。
     */
    @Test
    void getObject_returnsNullAndEvictsStaleKeyOnTypeMismatch() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 缓存中存的是 String，但调用方期望 Integer —— 模拟部署改类型后的脏 key
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn("not-an-integer");

        Integer result = redisService.getObject(SMS_KEY, "k", Integer.class);

        // 不抛异常，按缓存未命中返回 null
        assertThat(result).isNull();
        // 自愈：删除脏 key，下次调用可重新回源计算
        verify(redisTemplate, times(1)).delete("light-boot:auth:sms:code:k");
    }

    /**
     * getObject(Class) 在 key 不存在时返回 null，且不触发删除。
     */
    @Test
    void getObject_returnsNullWhenKeyAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);

        assertThat(redisService.getObject(SMS_KEY, "missing", String.class)).isNull();
        verify(redisTemplate, times(0)).delete(any(String.class));
    }

    /**
     * getObject(Class) 在类型匹配时正常返回强类型值。
     */
    @Test
    void getObject_returnsCastedValueWhenTypeMatches() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn(42);

        assertThat(redisService.getObject(SMS_KEY, "k", Integer.class)).isEqualTo(42);
        verify(redisTemplate, times(0)).delete(any(String.class));
    }

    /**
     * KeyDefinition 超时为 null（永不过期）时，setString 不携带过期时间写入，而非抛 NPE。
     */
    @Test
    void setString_withNullTimeout_writesWithoutExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setString(NEVER_EXPIRE_KEY, "1", "v");

        verify(valueOps).set(eq("light-boot:user:detail:1"), eq("v"));
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * KeyDefinition 超时为 null（永不过期）时，setObject 不携带过期时间写入，而非抛 NPE。
     */
    @Test
    void setObject_withNullTimeout_writesWithoutExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setObject(NEVER_EXPIRE_KEY, "1", "v");

        verify(valueOps).set(eq("light-boot:user:detail:1"), eq("v"));
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * KeyDefinition 超时为 null（永不过期）时，expire 不调用 Redis 并返回 false。
     */
    @Test
    void expire_withNullTimeout_skipsAndReturnsFalse() {
        assertThat(redisService.expire(NEVER_EXPIRE_KEY, "1")).isFalse();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    /**
     * setStringWithoutExpire 永不过期写入：即使 keyDef 定义了超时也不携带过期时间。
     */
    @Test
    void setStringWithoutExpire_ignoresKeyDefinitionTimeout() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setStringWithoutExpire(SMS_KEY, "k", "v");

        verify(valueOps).set(eq("light-boot:auth:sms:code:k"), eq("v"));
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * setObject 使用 KeyDefinition 的超时（分钟），并拼接全局前缀组成完整 key。
     */
    @Test
    void setObject_appliesKeyDefinitionTimeoutAndGlobalPrefix() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setObject(SMS_KEY, "k", Map.of("id", 1));

        verify(valueOps).set(eq("light-boot:auth:sms:code:k"), eq(Map.of("id", 1)),
                eq(Duration.ofMinutes(10)));
    }

    /**
     * setObject 支持自定义超时时长与单位。
     */
    @Test
    void setObject_acceptsCustomTimeoutAndUnit() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setObject(SMS_KEY, "k", "payload", 90, TimeUnit.SECONDS);

        verify(valueOps).set(eq("light-boot:auth:sms:code:k"), eq("payload"), eq(Duration.ofSeconds(90)));
    }

    /**
     * setObjectWithoutExpire 永不过期写入：即使 keyDef 定义了超时也不携带过期时间。
     */
    @Test
    void setObjectWithoutExpire_ignoresKeyDefinitionTimeout() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        redisService.setObjectWithoutExpire(SMS_KEY, "k", "v");

        verify(valueOps).set(eq("light-boot:auth:sms:code:k"), eq("v"));
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    /**
     * setStringIfAbsent：key 不存在时写入成功，透传 true，超时按自定义单位转换为 Duration。
     */
    @Test
    void setStringIfAbsent_returnsTrueAndAppliesTimeoutWhenKeyAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("light-boot:auth:sms:code:lock", "holder", Duration.ofSeconds(30)))
                .thenReturn(true);

        assertThat(redisService.setStringIfAbsent(SMS_KEY, "lock", "holder", 30, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * setStringIfAbsent：key 已存在时透传 false（SET NX 不覆盖原值的语义由 Redis 保证）。
     */
    @Test
    void setStringIfAbsent_returnsFalseWhenKeyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), any(), any(Duration.class))).thenReturn(false);

        assertThat(redisService.setStringIfAbsent(SMS_KEY, "lock", "holder", 30, TimeUnit.SECONDS)).isFalse();
    }

    /**
     * getObject（无类型参数）将 Redis 返回值原样透传，不做任何转换。
     */
    @Test
    void getObject_returnsValueAsIs() {
        Object payload = new Object();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("light-boot:auth:sms:code:k")).thenReturn(payload);

        assertThat(redisService.getObject(SMS_KEY, "k")).isSameAs(payload);
    }
}
