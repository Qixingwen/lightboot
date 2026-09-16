package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * RedisService ZSet 族单测：验证 zSetAdd（单值/批量）/zSetRemove/zSetRange/zSetReverseRange/
 * zSetRangeByScore/zSetScore/zSetRank/zSetSize 委托 {@code opsForZSet()} 对应方法、
 * 完整 key = 全局前缀 + 业务前缀 + 后缀、score/排名范围/分数边界等参数如实传递、
 * 返回值如实透传（含成员不存在时的 null）。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceZSetTest {

    /**
     * 测试用 KeyDefinition，前缀 rank:board:，永不过期（ZSet 族方法不消费超时）。
     */
    private static final RedisKeyDefinition RANK_KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "rank:board:";
        }

        @Override
        public Long getTimeout() {
            return null;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ZSetOperations<String, Object> zSetOps;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * 构建保持插入顺序的元素集合，用于验证范围查询结果的顺序透传。
     */
    private static Set<Object> linkedSet(Object... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    /**
     * zSetAdd（单值）将元素与分数如实传递，透传是否为新添加。
     */
    @Test
    void zSetAdd_returnsWhetherNewElementAdded() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.add("light-boot:rank:board:weekly", "player-1", 9.5)).thenReturn(true);

        assertThat(redisService.zSetAdd(RANK_KEY, "weekly", "player-1", 9.5)).isTrue();
    }

    /**
     * zSetAdd（批量）将 TypedTuple 集合如实传递，透传成功添加的数量。
     */
    @Test
    void zSetAdd_tuples_returnsAddedCount() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of("player-1", 9.5));
        tuples.add(ZSetOperations.TypedTuple.of("player-2", 8.5));
        when(zSetOps.add("light-boot:rank:board:weekly", tuples)).thenReturn(2L);

        assertThat(redisService.zSetAdd(RANK_KEY, "weekly", tuples)).isEqualTo(2L);
    }

    /**
     * zSetRemove 将元素逐个传给 opsForZSet.remove，并透传成功移除的数量。
     */
    @Test
    void zSetRemove_passesValuesAndReturnsRemovedCount() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.remove("light-boot:rank:board:weekly", "player-1", "player-2")).thenReturn(2L);

        assertThat(redisService.zSetRemove(RANK_KEY, "weekly", "player-1", "player-2")).isEqualTo(2L);
    }

    /**
     * zSetRange 将排名范围如实传递，并按升序顺序透传结果。
     */
    @Test
    void zSetRange_returnsElementsInAscendingRange() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.range("light-boot:rank:board:weekly", 0L, 9L))
                .thenReturn(linkedSet("player-1", "player-2"));

        assertThat(redisService.zSetRange(RANK_KEY, "weekly", 0, 9)).containsExactly("player-1", "player-2");
    }

    /**
     * zSetReverseRange 将排名范围如实传递，并按降序顺序透传结果。
     */
    @Test
    void zSetReverseRange_returnsElementsInDescendingRange() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRange("light-boot:rank:board:weekly", 0L, 1L))
                .thenReturn(linkedSet("player-3", "player-2"));

        assertThat(redisService.zSetReverseRange(RANK_KEY, "weekly", 0, 1))
                .containsExactly("player-3", "player-2");
    }

    /**
     * zSetRangeByScore 将分数上下界如实传递，并透传范围内的元素。
     */
    @Test
    void zSetRangeByScore_passesScoreBoundsAndReturnsElements() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore("light-boot:rank:board:weekly", 8.0, 9.0))
                .thenReturn(linkedSet("player-2"));

        assertThat(redisService.zSetRangeByScore(RANK_KEY, "weekly", 8.0, 9.0))
                .containsExactly("player-2");
    }

    /**
     * zSetScore 透传成员分数；成员不存在时透传 null。
     */
    @Test
    void zSetScore_returnsScoreAndNullWhenMemberAbsent() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.score("light-boot:rank:board:weekly", "player-1")).thenReturn(9.5);
        when(zSetOps.score("light-boot:rank:board:weekly", "missing")).thenReturn(null);

        assertThat(redisService.zSetScore(RANK_KEY, "weekly", "player-1")).isEqualTo(9.5);
        assertThat(redisService.zSetScore(RANK_KEY, "weekly", "missing")).isNull();
    }

    /**
     * zSetRank 透传成员升序排名（0-based）；成员不存在时透传 null。
     */
    @Test
    void zSetRank_returnsRankAndNullWhenMemberAbsent() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rank("light-boot:rank:board:weekly", "player-1")).thenReturn(0L);
        when(zSetOps.rank("light-boot:rank:board:weekly", "missing")).thenReturn(null);

        assertThat(redisService.zSetRank(RANK_KEY, "weekly", "player-1")).isZero();
        assertThat(redisService.zSetRank(RANK_KEY, "weekly", "missing")).isNull();
    }

    /**
     * zSetSize 透传有序集合基数。
     */
    @Test
    void zSetSize_returnsCardinality() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size("light-boot:rank:board:weekly")).thenReturn(3L);

        assertThat(redisService.zSetSize(RANK_KEY, "weekly")).isEqualTo(3L);
    }
}
