package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.constant.RedisConstants;
import cn.nextdev.lightboot.redis.exception.SequenceGenerationException;
import cn.nextdev.lightboot.redis.serializer.SequenceLongSerializer;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * 基于 Redis 的全局序列号生成服务。
 *
 * <p>利用 Redis {@code INCR} 原子操作生成全局唯一、严格递增的序列号。
 * 对外返回的 ID 格式为 {@code {prefix}{yyyyMMdd}{序号}}（如 {@code ORD202605110001}），每日自动重置；
 * Redis Key 在当天结束时过期，且每次生成都会「无条件」重置该过期时间（见
 * {@link #generate(String, LocalDate)} 与 {@link #INCREMENT_AND_EXPIRE_SCRIPT} 的说明）。
 *
 * @see RedisConstants#SEQUENCE_KEY_PREFIX
 * @see RedisConstants#INITIAL_SEQUENCE_LENGTH
 */
public class SequenceService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(RedisConstants.DATE_PATTERN);
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /**
     * 原子自增并「无条件」设置过期：每次 {@code INCR} 后都用 {@code PEXPIREAT} 把 Key 的过期时间
     * 重置为当天结束时刻，而非仅在 {@code seq==1} 时设置一次。
     *
     * <p>将 {@code INCR} 与 {@code PEXPIREAT} 合并到单个 Lua 脚本中执行，避免分两步调用时
     * 出现「INCR 成功但 EXPIRE 失败」导致 Key 永久残留的竞态。
     *
     * <p><b>为什么改为无条件</b>：原先仅在 {@code seq==1} 时执行一次 {@code PEXPIREAT}。一旦 Key 因
     * 时钟回拨、AOF 重放旧的 {@code PEXPIREAT}、或备份恢复而「幸存」下来，后续 {@code INCR} 不再
     * 重新设置过期，Key 将永久残留。改为每次调用都无条件重置过期时间——以绝对时间戳
     * ({@code ARGV[1]} = 当天结束的 epoch 毫秒) 调用 {@code PEXPIREAT}，幂等于重置、重放和恢复，
     * 不受相对 TTL 在跨日期/历史日期下出现负值的困扰。
     */
    private static final DefaultRedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT = new DefaultRedisScript<>(
            "local seq = redis.call('INCR', KEYS[1]) "
                    + "redis.call('PEXPIREAT', KEYS[1], ARGV[1]) "
                    + "return seq",
            Long.class
    );

    private final RedisTemplate<String, Object> redisTemplate;
    private final String globalPrefix;

    /**
     * 创建序列号生成服务实例。
     *
     * @param redisTemplate 框架提供的 {@code RedisTemplate<String, Object>}
     * @param globalPrefix  Key 全局前缀，通常来源于 {@code RedisProperties.keyPrefix}
     */
    public SequenceService(RedisTemplate<String, Object> redisTemplate, String globalPrefix) {
        this.redisTemplate = redisTemplate;
        this.globalPrefix = globalPrefix != null ? globalPrefix : "";
    }

    /**
     * 生成当天的序列号。
     *
     * @param prefix 业务前缀，如 {@code "ORD"}、{@code "PAY"}
     * @return 完整的序列号字符串，如 {@code "ORD202605110001"}
     * @throws SequenceGenerationException 当序列号生成失败时抛出
     */
    public String generate(String prefix) {
        return generate(prefix, LocalDate.now(ZONE_ID));
    }

    /**
     * 生成指定日期的序列号，使用 Redis Lua 脚本原子地完成 {@code INCR} 与当日过期设置，
     * 保证全局唯一性和严格递增。每次调用都「无条件」把 Key 的过期时间重置为当天结束时刻
     * （见 {@link #INCREMENT_AND_EXPIRE_SCRIPT} 的说明），从而在时钟回拨/AOF 重放/备份恢复后仍能正确重设过期。
     *
     * <p><b>Key 与生成 ID 的分隔符区别（重要）</b>：
     * <ul>
     *   <li>Redis Key 在业务前缀与日期之间插入 {@code ':'} 分隔符——
     *       {@code {keyPrefix}global:sequence:{prefix}:{date}}（如 {@code light-boot:global:sequence:ORD:20260511}），
     *       用于保证 Key 的唯一性，避免 {@code ORD}+{@code 20260511} 与 {@code ORD2}+{@code 0210511} 这类
     *       字符边界相邻的前缀产生歧义/碰撞。</li>
     *   <li>而对外返回的「生成 ID」仍保持紧凑无分隔格式 {@code {prefix}{date}{序号}}
     *       （如 {@code ORD202605110001}），这是既有的下游契约，改动会破坏消费方，故 <b>不</b> 在 ID 中加分隔符。</li>
     * </ul>
     *
     * @param prefix    业务前缀
     * @param localDate 指定日期
     * @return 完整的序列号字符串
     * @throws SequenceGenerationException 当序列号生成失败时抛出
     */
    // execute 契约为 @Nullable（Redis nil 回复或反序列化无值），其上的 null 检查是真实可达的防御分支，
    // 且被 generate_throwsWhenRedisReturnsNull 覆盖。IDE 据脚本返回类型误判该条件恒为 false，
    // 故抑制 ConstantConditions 误报——切勿据此删除下面的 null 检查。
    @SuppressWarnings("ConstantConditions")
    public String generate(String prefix, LocalDate localDate) {
        try {
            String datePart = localDate.format(DATE_FORMATTER);
            // Key 在 prefix 与 date 之间加 ':' 分隔符以保证唯一性；注意：返回的 ID 不加分隔符（见 Javadoc）
            String key = globalPrefix + RedisConstants.SEQUENCE_KEY_PREFIX + prefix + ":" + datePart;

            long endOfDayEpochMillis = localDate.atTime(LocalTime.MAX)
                    .atZone(ZONE_ID)
                    .toInstant()
                    .toEpochMilli();

            Long sequence = redisTemplate.execute(
                    INCREMENT_AND_EXPIRE_SCRIPT,
                    RedisSerializer.string(),
                    SequenceLongSerializer.INSTANCE,
                    Collections.singletonList(key),
                    String.valueOf(endOfDayEpochMillis)
            );
            if (sequence == null) {
                throw new SequenceGenerationException(prefix, localDate,
                        "Failed to generate sequence [prefix=" + prefix + ", date=" + datePart + "]: Redis returned null");
            }

            return String.format("%s%s%0" + calculateSequenceLength(sequence) + "d", prefix, datePart, sequence);
        } catch (SequenceGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new SequenceGenerationException(prefix, localDate,
                    "Failed to generate sequence [prefix=" + prefix + ", date=" + DATE_FORMATTER.format(localDate) + "]", e);
        }
    }

    /**
     * 序号不超过 {@link RedisConstants#MAX_INITIAL_SEQUENCE} 时使用 {@link RedisConstants#INITIAL_SEQUENCE_LENGTH} 位，
     * 超过后自动扩展为序号实际位数。
     *
     * @param sequence 序号值（从 1 开始）
     * @return 序号的字符串位数
     */
    private int calculateSequenceLength(long sequence) {
        if (sequence <= RedisConstants.MAX_INITIAL_SEQUENCE) {
            return RedisConstants.INITIAL_SEQUENCE_LENGTH;
        }
        return (int) (Math.log10(sequence) + 1);
    }

}
