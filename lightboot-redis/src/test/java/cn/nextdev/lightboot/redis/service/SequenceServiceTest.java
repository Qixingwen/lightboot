package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.exception.SequenceGenerationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SequenceService 单测：验证 key 拼接、序号位数扩展、Redis 异常包装。
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")  // RedisTemplate.execute varargs + RedisScript 泛型在 Mockito stub 下必然 unchecked
class SequenceServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    private SequenceService sequenceService;

    @BeforeEach
    void setUp() {
        sequenceService = new SequenceService(redisTemplate, "light-boot:");
    }

    /**
     * 序号在四位以内时左补零到四位。
     */
    @Test
    void generate_leftPadsToFourDigits() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(1L);

        String seq = sequenceService.generate("ORD", LocalDate.of(2026, 5, 11));

        assertThat(seq).isEqualTo("ORD202605110001");
    }

    /**
     * 序号恰好为 9999 时仍保持四位。
     */
    @Test
    void generate_keepsFourDigitsAtExact9999() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(9999L);

        String seq = sequenceService.generate("ORD", LocalDate.of(2026, 5, 11));

        assertThat(seq).isEqualTo("ORD202605119999");
    }

    /**
     * 序号超过 9999 时自动扩展位数。
     */
    @Test
    void generate_extendsDigitsBeyond9999() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(12345L);

        String seq = sequenceService.generate("ORD", LocalDate.of(2026, 5, 11));

        assertThat(seq).isEqualTo("ORD2026051112345");
    }

    /**
     * generate 使用带全局前缀的正确 key。
     */
    @Test
    void generate_usesKeyWithGlobalPrefix() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(1L);

        sequenceService.generate("PAY", LocalDate.of(2026, 5, 11));

        // 捕获传给 execute 的 keys 列表，验证完整 key
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(redisTemplate)
                .execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), keysCaptor.capture(), any());
        assertThat(keysCaptor.getValue()).containsExactly("light-boot:global:sequence:PAY:20260511");
    }

    /**
     * Redis 返回 null 时抛 SequenceGenerationException。
     */
    @Test
    void generate_throwsWhenRedisReturnsNull() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(null);

        assertThatThrownBy(() -> sequenceService.generate("ORD", LocalDate.of(2026, 5, 11)))
                .isInstanceOf(SequenceGenerationException.class)
                .hasMessageContaining("Failed to generate sequence");
    }

    /**
     * Redis 异常被包装为 SequenceGenerationException 上抛。
     */
    @Test
    void generate_wrapsRedisException() {
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any()))
                .thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> sequenceService.generate("ORD", LocalDate.of(2026, 5, 11)))
                .isInstanceOf(SequenceGenerationException.class)
                .hasMessageContaining("Failed to generate sequence")
                .hasRootCauseInstanceOf(RuntimeException.class);
    }

    /**
     * globalPrefix 为 null 时按空串处理，序列号正常生成。
     */
    @Test
    void globalPrefix_treatedAsEmptyWhenNull() {
        SequenceService noPrefix = new SequenceService(redisTemplate, null);
        when(redisTemplate.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), any(List.class), any())).thenReturn(1L);

        String seq = noPrefix.generate("ORD", LocalDate.of(2026, 5, 11));

        assertThat(seq).isEqualTo("ORD202605110001");
        // eq(any()) 为非法 Mockito 用法，按 brief 注释删除 verify 块，保留返回值断言
    }
}
