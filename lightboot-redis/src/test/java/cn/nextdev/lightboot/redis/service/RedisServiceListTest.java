package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisService List 族单测：验证 leftPush/rightPush/leftPop/rightPop/listRange/listSize/listTrim
 * 委托 {@code opsForList()} 对应方法、完整 key = 全局前缀 + 业务前缀 + 后缀、索引参数如实传递、
 * 返回值如实透传（含列表为空时的 null 与空列表）。
 * 全部基于 mock RedisTemplate，无真实 Redis 依赖。
 */
@ExtendWith(MockitoExtension.class)
class RedisServiceListTest {

    /**
     * 测试用 KeyDefinition，前缀 mq:email:queue:，永不过期（List 族方法不消费超时）。
     */
    private static final RedisKeyDefinition QUEUE_KEY = new RedisKeyDefinition() {
        @Override
        public String getPrefix() {
            return "mq:email:queue:";
        }

        @Override
        public Long getTimeout() {
            return null;
        }
    };

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ListOperations<String, Object> listOps;

    private RedisService redisService;

    @BeforeEach
    void setUp() {
        redisService = new RedisService(redisTemplate, "light-boot:");
    }

    /**
     * leftPush 左推入并透传推入后的列表长度。
     */
    @Test
    void leftPush_returnsNewListLength() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPush("light-boot:mq:email:queue:jobs", "job-1")).thenReturn(1L);

        assertThat(redisService.leftPush(QUEUE_KEY, "jobs", "job-1")).isEqualTo(1L);
    }

    /**
     * rightPush 右推入并透传推入后的列表长度。
     */
    @Test
    void rightPush_returnsNewListLength() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPush("light-boot:mq:email:queue:jobs", "job-2")).thenReturn(2L);

        assertThat(redisService.rightPush(QUEUE_KEY, "jobs", "job-2")).isEqualTo(2L);
    }

    /**
     * leftPop 弹出列表头部元素。
     */
    @Test
    void leftPop_returnsHeadElement() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop("light-boot:mq:email:queue:jobs")).thenReturn("job-1");

        assertThat(redisService.leftPop(QUEUE_KEY, "jobs")).isEqualTo("job-1");
    }

    /**
     * leftPop 在列表为空时透传 null。
     */
    @Test
    void leftPop_returnsNullWhenListEmpty() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop("light-boot:mq:email:queue:jobs")).thenReturn(null);

        assertThat(redisService.leftPop(QUEUE_KEY, "jobs")).isNull();
    }

    /**
     * rightPop 弹出列表尾部元素。
     */
    @Test
    void rightPop_returnsTailElement() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.rightPop("light-boot:mq:email:queue:jobs")).thenReturn("job-9");

        assertThat(redisService.rightPop(QUEUE_KEY, "jobs")).isEqualTo("job-9");
    }

    /**
     * listRange 将 start/end 索引如实传递并按顺序透传范围内的元素。
     */
    @Test
    void listRange_passesIndicesAndReturnsElements() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range("light-boot:mq:email:queue:jobs", 0L, -1L))
                .thenReturn(List.of("job-1", "job-2"));

        assertThat(redisService.listRange(QUEUE_KEY, "jobs", 0, -1)).containsExactly("job-1", "job-2");
    }

    /**
     * listRange 在 key 不存在时透传空列表。
     */
    @Test
    void listRange_returnsEmptyListWhenKeyAbsent() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.range("light-boot:mq:email:queue:missing", 0L, -1L)).thenReturn(List.of());

        assertThat(redisService.listRange(QUEUE_KEY, "missing", 0, -1)).isEmpty();
    }

    /**
     * listSize 透传列表长度。
     */
    @Test
    void listSize_returnsListLength() {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.size("light-boot:mq:email:queue:jobs")).thenReturn(3L);

        assertThat(redisService.listSize(QUEUE_KEY, "jobs")).isEqualTo(3L);
    }

    /**
     * listTrim 将裁剪范围如实传递给 opsForList.trim。
     */
    @Test
    void listTrim_trimsToGivenRange() {
        when(redisTemplate.opsForList()).thenReturn(listOps);

        redisService.listTrim(QUEUE_KEY, "jobs", 0, 99);

        verify(listOps).trim("light-boot:mq:email:queue:jobs", 0L, 99L);
    }
}
