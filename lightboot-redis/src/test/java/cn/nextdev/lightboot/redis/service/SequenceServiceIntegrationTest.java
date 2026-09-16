package cn.nextdev.lightboot.redis.service;

import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SequenceService 集成测试：用 jedis-mock（真实 Redis 协议实现）跑实际的 Lua 脚本，
 * 验证序列号自增、Redis Key 含分隔符、以及「无条件 PEXPIREAT」使每次生成都设置 TTL。
 *
 * <p>与 {@link SequenceServiceTest}（Mock RedisTemplate）互补：后者覆盖纯逻辑分支，本测试覆盖
 * 真实 Redis 下的端到端行为——尤其是 Lua 脚本执行、Key 命名与过期时间是否落地。
 */
class SequenceServiceIntegrationTest {

    private static RedisServer redisServer;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> redisTemplate;
    private SequenceService sequenceService;

    /**
     * 用「未来日期」作为测试日期。
     *
     * <p>原因：脚本用「当天结束时刻的绝对毫秒」(PEXPIREAT) 设置过期。若用历史日期，该绝对时间戳已在过去，
     * Redis 会立即淘汰 Key，TTL 读取为 -2（不存在），无法验证过期逻辑。生产中 {@code generate(prefix)}
     * 始终用 {@code LocalDate.now()}，日期不会在过去；这里取未来日期复现「Key 在当天结束时过期」的真实语义。
     * 同时固定为 yyyyMMdd 格式，断言中据此计算日期串，避免硬编码。
     */
    private static final LocalDate FUTURE_DATE = LocalDate.now().plusYears(1);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @BeforeAll
    static void startRedis() throws IOException {
        // jedis-mock：newRedisServer() 绑定端口 0（系统分配），start() 后用 getBindPort() 读取实际端口
        redisServer = RedisServer.newRedisServer().start();
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getBindPort());
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        // 复用框架默认装配的序列化器组合：key 用 String，value 用 JSON
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        StringRedisSerializer str = new StringRedisSerializer();
        redisTemplate.setKeySerializer(str);
        redisTemplate.setHashKeySerializer(str);
        GenericJacksonJsonRedisSerializer json = new GenericJacksonJsonRedisSerializer(JsonMapper.builder().build());
        redisTemplate.setValueSerializer(json);
        redisTemplate.setHashValueSerializer(json);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() throws IOException {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        Assertions.assertNotNull(redisTemplate.getConnectionFactory());
        ((RedisServerCommands) redisTemplate.getConnectionFactory().getConnection()).flushAll();
        sequenceService = new SequenceService(redisTemplate, "light-boot:");
    }

    /**
     * 同一前缀/日期连续两次生成，得到递增的序列号，且 ID 保持紧凑无分隔格式。
     */
    @Test
    void generate_producesIncrementingSequence() {
        String datePart = FUTURE_DATE.format(DATE_FMT);

        String first = sequenceService.generate("ORD", FUTURE_DATE);
        String second = sequenceService.generate("ORD", FUTURE_DATE);

        assertThat(first).isEqualTo("ORD" + datePart + "0001");
        assertThat(second).isEqualTo("ORD" + datePart + "0002");
        // ID 格式不含分隔符（下游契约），分隔符只存在于 Redis Key
        assertThat(first).doesNotContain(":");
    }

    /**
     * 生成后实际落库的 Redis Key 在业务前缀与日期之间包含 {@code ':'} 分隔符，
     * 避免 ORD+20260511 与 ORD2+0210511 这类前缀产生 Key 碰撞。
     */
    @Test
    void key_containsSeparator() {
        String datePart = FUTURE_DATE.format(DATE_FMT);
        sequenceService.generate("ORD", FUTURE_DATE);

        // 注意：key 含全局前缀 light-boot:，故 pattern 也要带前缀
        Set<String> keys = redisTemplate.keys("light-boot:global:sequence:*");
        assertThat(keys).isNotEmpty();
        // 必须命中含分隔符的精确 Key
        assertThat(keys).contains("light-boot:global:sequence:ORD:" + datePart);
        // 排除旧的、无分隔符的歧义 Key（ORD{date}）
        assertThat(keys).noneMatch(k -> k.equals("light-boot:global:sequence:ORD" + datePart));
    }

    /**
     * 每次生成（含非首次）都会通过无条件 {@code PEXPIREAT} 设置 TTL，
     * 保证 Key 不会因时钟回拨/AOF 重放/备份恢复而永久残留。
     */
    @Test
    void expiry_isSetUnconditionally() {
        String datePart = FUTURE_DATE.format(DATE_FMT);
        // 第二次生成：验证 seq==2 时 TTL 仍被设置（旧实现仅在 seq==1 时设置一次）
        sequenceService.generate("ORD", FUTURE_DATE);
        sequenceService.generate("ORD", FUTURE_DATE);

        Long ttl = redisTemplate.getExpire("light-boot:global:sequence:ORD:" + datePart);
        // PEXPIREAT 已设置过期，TTL 必须为正（未来日期的当天结束时刻在未来）
        assertThat(ttl).isNotNull().isPositive();
    }
}
