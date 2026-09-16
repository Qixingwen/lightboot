package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RedisService Set 族单测：验证 setAdd/setRemove/setMembers/setIsMember/setSize
 * 委托 {@code opsForSet()} 对应方法、完整 key = 全局前缀 + 业务前缀 + 后缀、
 * varargs 元素逐个传递、返回值如实透传（含空集合）。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceSetTest {

    /**
     * 测试用 KeyDefinition，前缀 user:roles:，永不过期（Set 族方法不消费超时）。
     */
    private static final RedisKeyDefinition ROLE_KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "user:roles:";
        }

        @Override
        public Long getTimeout() {
            return null;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private SetOperations<String, Object> setOps;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * setAdd 将元素逐个传给 opsForSet.add，并透传成功添加的数量。
     */
    @Test
    void setAdd_passesValuesAndReturnsAddedCount() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.add("light-boot:user:roles:1001", "admin", "editor")).thenReturn(2L);

        assertThat(redisService.setAdd(ROLE_KEY, "1001", "admin", "editor")).isEqualTo(2L);
    }

    /**
     * setRemove 将元素逐个传给 opsForSet.remove，并透传成功移除的数量。
     */
    @Test
    void setRemove_passesValuesAndReturnsRemovedCount() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.remove("light-boot:user:roles:1001", "admin")).thenReturn(1L);

        assertThat(redisService.setRemove(ROLE_KEY, "1001", "admin")).isEqualTo(1L);
    }

    /**
     * setMembers 返回集合中的所有元素。
     */
    @Test
    void setMembers_returnsAllMembers() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("light-boot:user:roles:1001")).thenReturn(Set.of("admin", "editor"));

        assertThat(redisService.setMembers(ROLE_KEY, "1001")).containsExactlyInAnyOrder("admin", "editor");
    }

    /**
     * setMembers 在 key 不存在时透传空集合。
     */
    @Test
    void setMembers_returnsEmptySetWhenKeyAbsent() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.members("light-boot:user:roles:missing")).thenReturn(Set.of());

        assertThat(redisService.setMembers(ROLE_KEY, "missing")).isEmpty();
    }

    /**
     * setIsMember 透传元素存在性判断结果（true 与 false 两个方向）。
     */
    @Test
    void setIsMember_returnsMembership() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.isMember("light-boot:user:roles:1001", "admin")).thenReturn(true);
        when(setOps.isMember("light-boot:user:roles:1001", "guest")).thenReturn(false);

        assertThat(redisService.setIsMember(ROLE_KEY, "1001", "admin")).isTrue();
        assertThat(redisService.setIsMember(ROLE_KEY, "1001", "guest")).isFalse();
    }

    /**
     * setSize 透传集合基数。
     */
    @Test
    void setSize_returnsCardinality() {
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(setOps.size("light-boot:user:roles:1001")).thenReturn(2L);

        assertThat(redisService.setSize(ROLE_KEY, "1001")).isEqualTo(2L);
    }
}
