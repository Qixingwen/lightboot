package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 容错版 {@link RedisService}：读操作在 Redis 数据访问异常时降级返回空值，不抛异常打断业务。
 *
 * <p><b>捕获范围：</b>仅捕获 {@link DataAccessException}（连接失败、超时等 Spring 数据访问异常）；
 * 值反序列化失败（如脏数据/类型不匹配）等非数据访问异常不在容错范围内，仍会上抛。
 *
 * <p><b>仅读操作容错</b>（返回值可安全为 null/空集合的方法）：getString、getObject、hash 读、
 * list 读、set 读、zset 读、hasKey。写操作（set/put/push/delete/expire/increment 等）继承父类，
 * 仍抛异常——静默吞写失败更危险。
 *
 * <p>由 {@code light-boot.redis.fault-tolerant=true} 开启时装配，替代默认 {@link RedisService} Bean。
 * 容错会掩盖 Redis 故障，应在知晓代价后开启。
 *
 * <p><b>破坏性读的已知代价（重要）：</b>{@code leftPop}/{@code rightPop} 是<b>破坏性读</b>（LPOP/RPOP），
 * 容错降级时静默返回 {@code null}。但 Redis 端可能<b>已经成功弹出</b>元素、而响应在回传途中丢失——
 * 此时调用方误以为「列表为空」，实际元素已丢失。因此<b>不要</b>在「不容忍消息丢失」的队列消费场景
 * 使用容错 pop；此类场景请关闭容错（{@code fault-tolerant=false}），让 pop 异常上抛触发上层重投递/补偿。
 */
@Slf4j
public class FaultTolerantRedisService extends RedisService {

    /**
     * 创建容错 RedisService（不记录指标）。
     *
     * @param redisTemplate Redis 操作模板
     * @param globalPrefix  全局 Key 前缀
     */
    public FaultTolerantRedisService(RedisTemplate<String, Object> redisTemplate, String globalPrefix) {
        super(redisTemplate, globalPrefix);
    }

    /**
     * 创建容错 RedisService，注入可选的 Redis 操作指标记录器 provider。
     *
     * @param redisTemplate           Redis 操作模板
     * @param globalPrefix            全局 Key 前缀
     * @param metricsRecorderProvider Redis 指标记录器 provider，可为 {@code null}
     */
    public FaultTolerantRedisService(RedisTemplate<String, Object> redisTemplate, String globalPrefix, ObjectProvider<RedisMetricsRecorder> metricsRecorderProvider) {
        super(redisTemplate, globalPrefix, metricsRecorderProvider);
    }

    /**
     * 执行一次容错读：调用真实读操作，捕获 {@link DataAccessException} 时记录统一格式的 WARN 并返回降级值。
     *
     * <p>收敛各读方法重复的「try super → catch → 统一日志 → 返回降级值」模板，保证日志格式一致
     * （{@code Redis {operation} fallback to {fallbackDesc} (key={key}): {msg}}）。
     *
     * @param operation    操作名（用于日志与定位，如 {@code "getObject"}）
     * @param key          操作 key 后缀（用于日志，不含全局前缀）
     * @param action       真实读操作（委托父类）
     * @param fallback     降级返回值（读异常时使用）
     * @param fallbackDesc 降级值的人类可读描述（用于日志，如 {@code "null"}/{@code "empty"}/{@code "false"}）
     * @param <T>          返回类型
     * @return 正常返回 action 的结果；读异常时返回 fallback
     */
    private <T> T readWithFallback(String operation, String key, Supplier<T> action, T fallback, String fallbackDesc) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.warn("Redis {} fallback to {} (key={}): {}", operation, fallbackDesc, key, e.getMessage());
            return fallback;
        }
    }

    // ==================== String 读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常。
     */
    @Override
    public String getString(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("getString", key, () -> super.getString(keyDef, key), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常。
     */
    @Override
    public Object getObject(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("getObject", key, () -> super.getObject(keyDef, key), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常（父类的类型自愈语义仍生效）。
     */
    @Override
    public <T> T getObject(RedisKeyDefinition keyDef, String key, Class<T> clazz) {
        return readWithFallback("getObject", key, () -> super.getObject(keyDef, key, clazz), null, "null");
    }

    // ==================== Hash 读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常。
     */
    @Override
    public Object get(RedisKeyDefinition keyDef, String key, String hashKey) {
        return readWithFallback("hash get", key, () -> super.get(keyDef, key, hashKey), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 Map，不抛异常。
     */
    @Override
    public Map<Object, Object> getHashEntries(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("getHashEntries", key, () -> super.getHashEntries(keyDef, key), Map.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code false}——调用方无法据此区分「不存在」与「故障降级」。
     */
    @Override
    public Boolean hasKey(RedisKeyDefinition keyDef, String key, String hashKey) {
        return readWithFallback("hash hasKey", key, () -> super.hasKey(keyDef, key, hashKey), false, "false");
    }

    // ==================== List 读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 List，不抛异常。
     */
    @Override
    public List<Object> listRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        return readWithFallback("listRange", key, () -> super.listRange(keyDef, key, start, end), List.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}（区别于「Key 不存在」的 {@code 0}），不抛异常。
     */
    @Override
    public Long listSize(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("listSize", key, () -> super.listSize(keyDef, key), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>破坏性读容错警告：</b>LPOP 是破坏性操作，异常降级返回 {@code null} 时，Redis 端可能已弹出元素。
     * 不容忍消息丢失的队列消费场景请勿使用容错 pop（详见类注释）。
     */
    @Override
    public Object leftPop(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("leftPop", key, () -> super.leftPop(keyDef, key), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>破坏性读容错警告：</b>RPOP 是破坏性操作，异常降级返回 {@code null} 时，Redis 端可能已弹出元素。
     * 不容忍消息丢失的队列消费场景请勿使用容错 pop（详见类注释）。
     */
    @Override
    public Object rightPop(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("rightPop", key, () -> super.rightPop(keyDef, key), null, "null");
    }

    // ==================== Set 读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 Set，不抛异常。
     */
    @Override
    public Set<Object> setMembers(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("setMembers", key, () -> super.setMembers(keyDef, key), Set.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code false}——调用方无法据此区分「不存在」与「故障降级」。
     */
    @Override
    public Boolean setIsMember(RedisKeyDefinition keyDef, String key, Object value) {
        return readWithFallback("setIsMember", key, () -> super.setIsMember(keyDef, key, value), false, "false");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}（区别于「Key 不存在」的 {@code 0}），不抛异常。
     */
    @Override
    public Long setSize(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("setSize", key, () -> super.setSize(keyDef, key), null, "null");
    }

    // ==================== ZSet 读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 Set，不抛异常。
     */
    @Override
    public Set<Object> zSetRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        return readWithFallback("zSetRange", key, () -> super.zSetRange(keyDef, key, start, end), Set.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 Set，不抛异常。
     */
    @Override
    public Set<Object> zSetReverseRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        return readWithFallback("zSetReverseRange", key, () -> super.zSetReverseRange(keyDef, key, start, end), Set.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回空 Set，不抛异常。
     */
    @Override
    public Set<Object> zSetRangeByScore(RedisKeyDefinition keyDef, String key, double min, double max) {
        return readWithFallback("zSetRangeByScore", key, () -> super.zSetRangeByScore(keyDef, key, min, max), Set.of(), "empty");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常。
     */
    @Override
    public Double zSetScore(RedisKeyDefinition keyDef, String key, Object value) {
        return readWithFallback("zSetScore", key, () -> super.zSetScore(keyDef, key, value), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}，不抛异常。
     */
    @Override
    public Long zSetRank(RedisKeyDefinition keyDef, String key, Object value) {
        return readWithFallback("zSetRank", key, () -> super.zSetRank(keyDef, key, value), null, "null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code null}（区别于「Key 不存在」的 {@code 0}），不抛异常。
     */
    @Override
    public Long zSetSize(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("zSetSize", key, () -> super.zSetSize(keyDef, key), null, "null");
    }

    // ==================== 通用读 ====================

    /**
     * {@inheritDoc}
     *
     * <p>容错降级：Redis 数据访问异常时返回 {@code false}——调用方无法据此区分「不存在」与「故障降级」。
     */
    @Override
    public Boolean hasKey(RedisKeyDefinition keyDef, String key) {
        return readWithFallback("hasKey", key, () -> super.hasKey(keyDef, key), false, "false");
    }

    // 注：写操作（setString/setObject/put/leftPush/rightPush/setAdd/zSetAdd/delete/expire/increment 等）不重写，继承父类行为——Redis 写失败应抛异常而非静默吞掉。
}
