package cn.nextdev.lightboot.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 Redis ZSET 滑动窗口的限流计数服务。
 *
 * <p>算法：先移除窗口外的过期成员，统计窗口内成员数；若已达上限则刷新 TTL 后直接拒绝（不入队），
 * 否则 ZADD 当前请求再返回新计数。整个过程封装在单个 Lua 脚本中保证原子性。
 *
 * <p>序列化：脚本 args 与 result 使用 {@link RedisSerializer#string()} 与
 * {@link RateLimitLongSerializer}，绕过值序列化器（JSON），避免 ARGV 被加引号导致 tonumber 失败。
 *
 * <p>容错：Redis 异常时按 {@code failOpen} 决策——默认放行（true），避免 Redis 抖动阻断全部业务。
 * 降级发生时会以限频 WARN（每分钟至多 1 条）记录，保证 Redis 故障期间限流失效可被感知。
 */
// execute 契约为 @Nullable（Redis nil 回复或反序列化无值），其上的 null 检查是真实可达的防御分支，
// 由 RateLimitRedisServiceTest 的 allow_failOpensWhenLuaReturnsNull / allow_failClosesWhenConfigured
// 覆盖。IDE 据脚本返回类型误判该条件恒为 false，故抑制 ConstantConditions 误报——
// 切勿据此删除 allow() 中的 null 检查。
@SuppressWarnings("ConstantConditions")
public class RateLimitRedisService {

    /**
     * 滑动窗口限流 Lua 脚本（先判后记）。
     *
     * <p>KEYS[1]=限流 key；ARGV[1]=窗口起始毫秒，ARGV[2]=当前毫秒，ARGV[3]=唯一成员，ARGV[4]=窗口最大次数。
     * <p>返回：若本次入队则返回新计数（{@code current+1}）；若已达上限（拒绝、不入队）则返回
     * {@code current+1}（即「本次若计入将达到的数量」，恒大于 limit），调用方据此 {@code <= count} 判定。
     * 两条路径均不入队被拒请求，避免污染窗口。
     */
    @SuppressWarnings("SpellCheckingInspection")
    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1]) "
                    + "local current = redis.call('ZCARD', KEYS[1]) "
                    + "local limit = tonumber(ARGV[4]) "
                    + "if current >= limit then "
                    + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) - tonumber(ARGV[1]) + 1000) "
                    + "  return current + 1 "
                    + "end "
                    + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3]) "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2]) - tonumber(ARGV[1]) + 1000) "
                    + "return current + 1",
            Long.class
    );

    private static final Logger log = LoggerFactory.getLogger(RateLimitRedisService.class);

    /**
     * 降级告警的最小间隔（毫秒）：Redis 故障期间避免日志洪水。
     */
    private static final long FALLBACK_WARN_INTERVAL_MS = 60_000L;

    private final RedisTemplate<String, Object> redisTemplate;
    private final boolean failOpen;
    private final AtomicLong lastFallbackWarnAt = new AtomicLong();

    /**
     * 创建限流计数服务。
     *
     * @param redisTemplate Redis 操作模板
     * @param failOpen      Redis 异常时是否放行（true=放行，false=拒绝）
     */
    public RateLimitRedisService(RedisTemplate<String, Object> redisTemplate, boolean failOpen) {
        this.redisTemplate = redisTemplate;
        this.failOpen = failOpen;
    }

    /**
     * 判断指定 key 在滑动窗口内是否仍允许通过。
     *
     * @param key   限流 key（已含命名空间前缀）
     * @param time  时间窗口（秒）
     * @param count 窗口内最大允许次数
     * @return true 表示放行（当前计数 {@code <= count}），false 表示已超限；
     * Redis 异常或脚本返回 null 时不抛出，按 {@code failOpen} 决策（见类注释）
     */
    public boolean allow(String key, int time, int count) {
        long now = System.currentTimeMillis();
        long windowStart = now - time * 1000L;
        // 唯一成员：时间戳 + UUID，跨实例同毫秒不碰撞
        String member = now + "-" + UUID.randomUUID();
        List<String> keys = Collections.singletonList(key);
        try {
            Long current = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    RedisSerializer.string(),
                    RateLimitLongSerializer.INSTANCE,
                    keys,
                    String.valueOf(windowStart), String.valueOf(now), member, String.valueOf(count)
            );
            // execute 契约为 @Nullable（Redis nil 回复或反序列化无值时返回 null）：此时无法确定计数，
            // 按 failOpen 决策，与下方的 DataAccessException 容错路径保持一致，绝不拆箱抛 NPE。
            if (current == null) {
                warnFallback("script returned null", null);
                return failOpen;
            }
            return current <= count;
        } catch (DataAccessException e) {
            // Redis 异常：按 failOpen 决策，统一不抛出（避免以 500 形式上抛）
            warnFallback(e.getClass().getSimpleName(), e);
            return failOpen;
        }
    }

    /**
     * 限频记录降级告警：每 {@value #FALLBACK_WARN_INTERVAL_MS}ms 至多一条。
     * CAS 保证并发下仅一个线程胜出输出。
     *
     * @param reason 降级原因描述（如 "script returned null" / 异常类名）
     * @param cause  底层异常（可为 null）
     */
    private void warnFallback(String reason, Throwable cause) {
        long now = System.currentTimeMillis();
        long prev = lastFallbackWarnAt.get();
        if (now - prev >= FALLBACK_WARN_INTERVAL_MS && lastFallbackWarnAt.compareAndSet(prev, now)) {
            log.warn("限流降级生效：Redis 不可用（{}），按 failOpen={} 决策", reason, failOpen, cause);
        }
    }
}
