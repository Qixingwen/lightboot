package cn.nextdev.lightboot.ratelimit;

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
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitRedisService 集成测试：用 jedis-mock（真实 Redis 协议 + luaj 真实执行 Lua）跑
 * {@link RateLimitRedisService} 内置的滑动窗口脚本，验证限流正确性——这是纯 Mockito 单测
 * （{@link RateLimitRedisServiceTest}）无法覆盖的部分。
 *
 * <p>脚本内 4 条 Redis 命令在 jedis-mock 1.1.19 均有真实实现（逐条核实过对应操作类）：
 * ZREMRANGEBYSCORE / ZCARD / PEXPIRE / ZADD，脚本本体经 EVAL（EVALSHA→EVAL 回退）执行。
 *
 * <p>连接与序列化器接法对齐 {@code RateLimitRedisService} 主代码：模板 key 用 String；
 * Lua args/result 由服务内部指定 {@code RedisSerializer#string()} 与
 * {@link RateLimitLongSerializer}（脚本路径不经过模板的值序列化器）。
 */
class RateLimitRedisServiceLuaIntegrationTest {

    private static RedisServer redisServer;
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, Object> redisTemplate;
    private RateLimitRedisService service;

    @BeforeAll
    static void startRedis() throws IOException {
        // jedis-mock：newRedisServer() 绑定端口 0（系统分配），start() 后用 getBindPort() 读取实际端口
        redisServer = RedisServer.newRedisServer().start();
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration(redisServer.getHost(), redisServer.getBindPort());
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        // key 用 String（KEYS[1] 走 keySerializer）；value 序列化器与本模块装配无关，
        // 且脚本路径（execute(script, argsSerializer, resultSerializer, ...)）完全不经过它，
        // 故统一用 String 即可（Lua args/result 由服务内部指定 string/RateLimitLongSerializer）
        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        StringRedisSerializer str = new StringRedisSerializer();
        redisTemplate.setKeySerializer(str);
        redisTemplate.setHashKeySerializer(str);
        redisTemplate.setValueSerializer(str);
        redisTemplate.setHashValueSerializer(str);
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
        service = new RateLimitRedisService(redisTemplate, true);
    }

    /**
     * 窗口内首次调用放行，且请求作为成员真实入队（ZADD 生效，ZCARD=1）。
     */
    @Test
    void allow_firstCallInWindow_passesAndRecordsMember() {
        String key = "rate:it:first";

        assertThat(service.allow(key, 60, 10)).isTrue();
        assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo(1L);
    }

    /**
     * 边界语义 {@code current <= count}：第 count 次仍放行，第 count+1 次拒绝；
     * 被拒请求不入队（脚本「先判后记」，窗口成员数保持 count，不污染后续统计）。
     */
    @Test
    void allow_allowsExactlyCountRequests_rejectsBeyond_withoutPollutingWindow() {
        String key = "rate:it:boundary";

        assertThat(service.allow(key, 60, 3)).isTrue();
        assertThat(service.allow(key, 60, 3)).isTrue();
        // 第 3 次（count 次）：current=2，入队后 3 <= 3 → 放行
        assertThat(service.allow(key, 60, 3)).isTrue();
        // 第 4 次：current=3 >= limit → 不入队、拒绝
        assertThat(service.allow(key, 60, 3)).isFalse();

        assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo(3L);
    }

    /**
     * 脚本的 PEXPIRE 真实生效：放行后 key 带有正的存活时间
     * （TTL = 窗口秒数 + 1 秒缓冲，保证窗口成员清理前 key 不先消失）。
     */
    @Test
    void allow_setsPositiveTtlOnKey() {
        String key = "rate:it:ttl";

        assertThat(service.allow(key, 60, 10)).isTrue();

        Long ttl = redisTemplate.getExpire(key);
        assertThat(ttl).isNotNull().isPositive();
    }

    /**
     * 不同 key 相互独立：key a 打满拒绝后，key b 仍按自己的窗口放行。
     */
    @Test
    void allow_keysAreIndependent() {
        String keyA = "rate:it:indep-a";
        String keyB = "rate:it:indep-b";

        assertThat(service.allow(keyA, 60, 1)).isTrue();
        assertThat(service.allow(keyA, 60, 1)).isFalse();
        // b 与 a 是不同 ZSET，不受 a 已满影响
        assertThat(service.allow(keyB, 60, 1)).isTrue();
    }

    /**
     * 窗口滑动：塞满并拒绝后等待窗口滑出，再次调用重新放行。
     *
     * <p>关键点：先把 TTL 手动续到 60s，排除「key 整体过期导致放行」的混淆变量——
     * 此后放行只能来自脚本用 ZREMRANGEBYSCORE 清掉了窗口外旧成员（时间窗 1s 已滑出）。
     * 清理后的 ZCARD=1（仅本次新入队成员）：若清理失效，ZCARD 仍为 3、本次必拒绝。
     */
    @Test
    void allow_windowSlides_reamitsAfterOldMembersPurged() throws InterruptedException {
        String key = "rate:it:slide";

        assertThat(service.allow(key, 1, 2)).isTrue();
        assertThat(service.allow(key, 1, 2)).isTrue();
        assertThat(service.allow(key, 1, 2)).isFalse();

        // 续期排除 key 过期变量，隔离验证 ZREMRANGEBYSCORE 的清理行为
        redisTemplate.expire(key, Duration.ofSeconds(60));
        assertThat(redisTemplate.hasKey(key)).isTrue();

        // 等待 1 秒窗口整体滑出（老成员时间戳全部落在窗口起点之前）
        Thread.sleep(1100);

        assertThat(service.allow(key, 1, 2)).isTrue();
        // 仅剩本次新入队的成员
        assertThat(redisTemplate.opsForZSet().zCard(key)).isEqualTo(1L);
    }
}
