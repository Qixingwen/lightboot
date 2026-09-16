package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FaultTolerantRedisService 单测：验证读操作容错降级、写操作仍抛异常。
 *
 * <p>测试方式：通过 stub 底层 {@link RedisTemplate} 的 ops 操作抛 {@link RedisSystemException}
 * （{@code DataAccessException} 子类），使 {@code super.读方法()} 真实抛出，从而触发子类重写的 catch。
 * 这能完整覆盖「父类读方法抛异常→子类 catch 降级」的真实代码路径。
 * 注：不能用 {@code Mockito.spy(service) + doThrow().when(spy).readMethod()}，因为 stub 会短路
 * 重写方法本身，使 {@code super.*} 永不执行、catch 块无法触达。
 */
@ExtendWith(MockitoExtension.class)
class FaultTolerantRedisServiceTest {

    private static final RedisKeyDefinition KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "k:";
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
    private SetOperations<String, Object> setOps;

    private FaultTolerantRedisService service;

    @BeforeEach
    void setUp() {
        service = new FaultTolerantRedisService(redisTemplate, "light-boot:");
    }

    /**
     * 读容错：String 读异常时返回 null 不抛。
     */
    @Test
    void getString_returnsNullOnRedisException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any(String.class)))
                .thenThrow(new RedisSystemException("down", new RuntimeException()));

        assertThat(service.getString(KEY, "x")).isNull();
    }

    /**
     * 读容错：Set 读异常时返回空集合不抛。
     */
    @Test
    void setMembers_returnsEmptySetOnRedisException() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members(any(String.class)))
                .thenThrow(new RedisSystemException("down", new RuntimeException()));

        Set<Object> result = service.setMembers(KEY, "x");
        assertThat(result).isNotNull().isEmpty();
    }

    /**
     * 读容错：通用 hasKey 异常时返回 false 不抛。
     */
    @Test
    void hasKey_returnsFalseOnRedisException() {
        when(redisTemplate.hasKey(any(String.class)))
                .thenThrow(new RedisSystemException("down", new RuntimeException()));

        assertThat(service.hasKey(KEY, "x")).isFalse();
    }

    /**
     * 写仍抛异常：写操作继承父类不重写，Redis 异常应抛出而非静默吞掉。
     */
    @Test
    void setString_stillThrowsOnRedisException() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        org.mockito.Mockito.doThrow(new RedisSystemException("down", new RuntimeException()))
                .when(valueOps).set(any(String.class), any(Object.class), any(java.time.Duration.class));

        assertThatThrownBy(() -> service.setString(KEY, "x", "v"))
                .isInstanceOf(RedisSystemException.class);
    }

    /**
     * 正常读取透传父类结果，不降级。
     */
    @Test
    void getString_passesThroughParentResultOnNormalRead() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any(String.class))).thenReturn("hello");

        assertThat(service.getString(KEY, "x")).isEqualTo("hello");
    }
}
