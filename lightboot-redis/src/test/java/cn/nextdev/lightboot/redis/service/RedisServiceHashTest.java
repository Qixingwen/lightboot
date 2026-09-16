package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisService Hash 族单测：验证 put/get/hashDelete/hasKey/putAll/getHashEntries
 * 委托 {@code opsForHash()} 对应方法、完整 key = 全局前缀 + 业务前缀 + 后缀、返回值如实透传（含 null/空 Map）。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceHashTest {

    /**
     * 测试用 KeyDefinition，前缀 user:profile:，超时 30 分钟。
     */
    private static final RedisKeyDefinition PROFILE_KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "user:profile:";
        }

        @Override
        public Long getTimeout() {
            return 30L;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * put 写入单个字段，key 拼接全局前缀。
     */
    @Test
    void put_writesFieldWithGlobalPrefix() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        redisService.put(PROFILE_KEY, "1001", "nickname", "tom");

        verify(hashOps).put("light-boot:user:profile:1001", "nickname", "tom");
    }

    /**
     * get 读取单个字段值。
     */
    @Test
    void get_returnsFieldValue() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("light-boot:user:profile:1001", "nickname")).thenReturn("tom");

        assertThat(redisService.get(PROFILE_KEY, "1001", "nickname")).isEqualTo("tom");
    }

    /**
     * get 在字段不存在时透传 null。
     */
    @Test
    void get_returnsNullWhenFieldAbsent() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("light-boot:user:profile:1001", "missing")).thenReturn(null);

        assertThat(redisService.get(PROFILE_KEY, "1001", "missing")).isNull();
    }

    /**
     * hashDelete 将字段名逐个传给 opsForHash.delete，并透传删除数量。
     */
    @Test
    void hashDelete_passesFieldNamesAndReturnsDeletedCount() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.delete("light-boot:user:profile:1001", "nickname", "avatar")).thenReturn(2L);

        assertThat(redisService.hashDelete(PROFILE_KEY, "1001", "nickname", "avatar")).isEqualTo(2L);
    }

    /**
     * hasKey 判断字段是否存在，透传布尔结果。
     */
    @Test
    void hasKey_returnsFieldExistence() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.hasKey("light-boot:user:profile:1001", "nickname")).thenReturn(true);

        assertThat(redisService.hasKey(PROFILE_KEY, "1001", "nickname")).isTrue();
    }

    /**
     * putAll 批量写入字段-值映射，key 拼接全局前缀，映射原样传递。
     */
    @Test
    void putAll_writesMapWithGlobalPrefix() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        Map<String, String> fields = Map.of("nickname", "tom", "city", "hz");

        redisService.putAll(PROFILE_KEY, "1001", fields);

        verify(hashOps).putAll("light-boot:user:profile:1001", fields);
    }

    /**
     * getHashEntries 返回全部字段和值。
     */
    @Test
    void getHashEntries_returnsAllEntries() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("light-boot:user:profile:1001")).thenReturn(Map.of("nickname", "tom"));

        assertThat(redisService.getHashEntries(PROFILE_KEY, "1001")).containsEntry("nickname", "tom");
    }

    /**
     * getHashEntries 在 key 不存在时透传空 Map。
     */
    @Test
    void getHashEntries_returnsEmptyMapWhenKeyAbsent() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("light-boot:user:profile:missing")).thenReturn(Map.of());

        assertThat(redisService.getHashEntries(PROFILE_KEY, "missing")).isEmpty();
    }
}
