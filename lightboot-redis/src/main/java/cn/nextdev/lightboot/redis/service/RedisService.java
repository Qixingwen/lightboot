package cn.nextdev.lightboot.redis.service;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import cn.nextdev.lightboot.redis.metrics.RedisMetricsRecorder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 统一 Redis 操作服务，封装常用的 Redis 数据结构操作。
 *
 * <p>所有方法均接受 {@link RedisKeyDefinition} 接口参数，各业务模块只需让枚举实现该接口即可直接使用。
 * 涵盖 String、Hash、List、Set、Sorted Set 及通用操作。
 *
 * <p><b>异常契约（基线）：</b>Redis 数据访问异常时直接抛出 {@link org.springframework.dao.DataAccessException}，
 * 不做容错；读操作的容错降级变体见 {@link FaultTolerantRedisService}。
 *
 * <p>通过 {@code @Getter} 暴露 {@link #redisTemplate}，以支持需要直接使用 Template 的场景。
 *
 * <p><b>指标埋点范围：</b>仅对<b>写操作</b>（set/hset/lpush/sadd/zadd/incr/decr/delete/expire 等所有变更类）
 * 与<b>重读操作</b>（get/getObject/hgetall/smembers/lrange/zrange/zrangebyscore 等可能拉大 payload 的读取）
 * 经 {@link #recordOp} 记录 {@code light-boot.redis.operations} Timer（tag: {@code operation}/{@code result}），
 * 操作名为固定字面量以避免 tag 基数膨胀。轻量点查询（exists/hexists/sismember/zscore/zrank/scard/llen/zcard/hget）
 * <b>不埋点</b>——这类查询即使慢也是被大 key 拖累，信号会体现在写或重读上；且埋点开销可能接近命令本身，故舍去以避免测量干扰。
 * 引入方未装配 actuator（无 {@link RedisMetricsRecorder}）时埋点为无操作，不影响业务。
 *
 * @see RedisKeyDefinition
 */
@Slf4j
public class RedisService {

    /**
     * 底层 Redis 操作模板，通过 {@link Getter} 暴露以支持需要直接使用 Template 的场景。
     */
    @Getter
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 全局 Key 前缀，来源于 {@code RedisProperties.keyPrefix}，用于项目/环境隔离。
     */
    private final String globalPrefix;

    /**
     * Redis 操作指标记录器 provider，仅在引入方装配 actuator（提供 {@link RedisMetricsRecorder}）时可用。
     * 通过 {@link ObjectProvider} 实现 null 安全：记录器缺失时 {@link #recordOp} 直接跳过，不影响业务。
     */
    private final ObjectProvider<RedisMetricsRecorder> metricsRecorderProvider;

    /**
     * 创建 RedisService 实例（不记录指标）。
     *
     * <p>保留该构造便于测试与无 actuator 场景直接实例化；指标记录器为 {@code null}，{@link #recordOp} 无操作。
     *
     * @param redisTemplate 框架提供的 {@code RedisTemplate<String, Object>}
     * @param globalPrefix  全局 Key 前缀，例如 {@code "light-boot:"}
     */
    public RedisService(RedisTemplate<String, Object> redisTemplate, String globalPrefix) {
        this(redisTemplate, globalPrefix, null);
    }

    /**
     * 创建 RedisService 实例，注入可选的 Redis 操作指标记录器 provider。
     *
     * @param redisTemplate           框架提供的 {@code RedisTemplate<String, Object>}
     * @param globalPrefix            全局 Key 前缀，例如 {@code "light-boot:"}
     * @param metricsRecorderProvider Redis 指标记录器 provider，可为 {@code null}（无 actuator 时）
     */
    public RedisService(RedisTemplate<String, Object> redisTemplate, String globalPrefix, ObjectProvider<RedisMetricsRecorder> metricsRecorderProvider) {
        this.redisTemplate = redisTemplate;
        this.globalPrefix = globalPrefix != null ? globalPrefix : "";
        this.metricsRecorderProvider = metricsRecorderProvider;
    }

    /**
     * 记录一次 Redis 操作的耗时与结果。当指标记录器未装配（无 actuator）时为无操作，保证 null 安全。
     *
     * @param operation  操作名（固定字面量，如 {@code "get"}/{@code "set"}，避免动态内容造成 tag 基数膨胀）
     * @param startNanos 操作开始时刻（{@code System.nanoTime()}），用于计算耗时
     * @param success    操作是否成功
     */
    private void recordOp(String operation, long startNanos, boolean success) {
        if (metricsRecorderProvider == null) {
            return;
        }
        RedisMetricsRecorder recorder = metricsRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        recorder.record(operation, durationMs, success);
    }

    /**
     * 构建完整的 Redis Key：全局前缀 + 业务前缀 + 后缀。
     *
     * @param keyDef Key 定义
     * @param suffix Key 后缀
     * @return 完整的 Redis Key
     */
    private String buildFullKey(RedisKeyDefinition keyDef, String suffix) {
        return globalPrefix + keyDef.buildKey(suffix);
    }

    /**
     * 将超时时长与单位转换为 {@link java.time.Duration}。
     *
     * @param timeout 超时时长，{@code null} 表示永不过期
     * @param unit    超时时间单位
     * @return 对应的 {@link java.time.Duration}；{@code timeout} 为 {@code null} 时返回 {@code null}
     */
    private static java.time.Duration toDuration(Long timeout, TimeUnit unit) {
        return timeout == null ? null : java.time.Duration.of(timeout, unit.toChronoUnit());
    }

    // ==================== String 操作 ====================

    /**
     * 设置 String 值，使用 {@link RedisKeyDefinition#getTimeout()} 定义的超时时间（分钟）；
     * keyDef 超时为 {@code null} 时永不过期。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要设置的字符串值
     */
    public void setString(RedisKeyDefinition keyDef, String key, String value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            java.time.Duration timeout = toDuration(keyDef.getTimeout(), TimeUnit.MINUTES);
            if (timeout == null) {
                redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value);
            } else {
                redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value, timeout);
            }
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 设置 String 值，自定义超时时间和单位。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param value   要设置的字符串值
     * @param timeout 超时时间
     * @param unit    超时时间单位
     */
    public void setString(RedisKeyDefinition keyDef, String key, String value, long timeout, TimeUnit unit) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value, toDuration(timeout, unit));
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 设置 String 值，永不过期。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要设置的字符串值
     */
    public void setStringWithoutExpire(RedisKeyDefinition keyDef, String key, String value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value);
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 设置 Object 值（JSON 序列化），使用 {@link RedisKeyDefinition#getTimeout()} 定义的超时时间（分钟）；
     * keyDef 超时为 {@code null} 时永不过期。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要存储的对象
     */
    public void setObject(RedisKeyDefinition keyDef, String key, Object value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            java.time.Duration timeout = toDuration(keyDef.getTimeout(), TimeUnit.MINUTES);
            if (timeout == null) {
                redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value);
            } else {
                redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value, timeout);
            }
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 设置 Object 值，自定义超时时间和单位。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param value   要存储的对象
     * @param timeout 超时时间
     * @param unit    超时时间单位
     */
    public void setObject(RedisKeyDefinition keyDef, String key, Object value, long timeout, TimeUnit unit) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value, toDuration(timeout, unit));
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 设置 Object 值，永不过期。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要存储的对象
     */
    public void setObjectWithoutExpire(RedisKeyDefinition keyDef, String key, Object value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForValue().set(buildFullKey(keyDef, key), value);
            success = true;
        } finally {
            recordOp("set", start, success);
        }
    }

    /**
     * 仅当 Key 不存在时设置值（{@code SET NX}）。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param value   要设置的字符串值
     * @param timeout 超时时间
     * @param unit    超时时间单位
     * @return {@code true} 表示设置成功，{@code false} 表示 Key 已存在
     */
    public Boolean setStringIfAbsent(RedisKeyDefinition keyDef, String key, String value, long timeout, TimeUnit unit) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Boolean created = redisTemplate.opsForValue().setIfAbsent(buildFullKey(keyDef, key), value, toDuration(timeout, unit));
            success = true;
            return created;
        } finally {
            recordOp("setnx", start, success);
        }
    }

    /**
     * 获取 String 值。
     *
     * <p>值为非 {@code String} 类型时返回其 {@code toString()} 结果
     * （如对 {@code setObject(...)} 存入的对象调用本方法，得到的是 {@code toString()} 而非原始 JSON）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 字符串值，Key 不存在时返回 {@code null}
     */
    public String getString(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object value = redisTemplate.opsForValue().get(buildFullKey(keyDef, key));
            success = true;
            if (value instanceof String s) {
                return s;
            }
            return value != null ? value.toString() : null;
        } finally {
            recordOp("get", start, success);
        }
    }

    /**
     * 获取 Object 值（经 JSON 反序列化）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 反序列化后的对象，Key 不存在时返回 {@code null}
     */
    public Object getObject(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object value = redisTemplate.opsForValue().get(buildFullKey(keyDef, key));
            success = true;
            return value;
        } finally {
            recordOp("get", start, success);
        }
    }

    /**
     * 获取 Object 值并转换为指定类型。
     *
     * <p>类型自愈：若缓存值类型与 {@code clazz} 不匹配（例如部署改了 DTO、或同一 key 上发生类型碰撞），
     * 不会抛 {@link ClassCastException}，而是记录告警、删除脏 key，并按缓存未命中语义返回 {@code null}，
     * 让调用方回源重算。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param clazz  目标类型
     * @param <T>    返回类型
     * @return 转换后的对象；Key 不存在或类型不匹配时返回 {@code null}
     */
    public <T> T getObject(RedisKeyDefinition keyDef, String key, Class<T> clazz) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object value = redisTemplate.opsForValue().get(buildFullKey(keyDef, key));
            if (value == null) {
                success = true;
                return null;
            }
            if (!clazz.isInstance(value)) {
                // 类型不匹配（例如部署改了 DTO、或同一 key 上发生类型碰撞）：记录告警并删除脏 key，
                // 按缓存未命中语义返回 null，让调用方回源重算——自愈，而非每个请求持续抛 ClassCastException 直到 TTL。
                log.warn("Redis 缓存类型不匹配，删除脏 key 回源重算。expected={}, actual={}, key={}",
                        clazz.getName(), value.getClass().getName(), buildFullKey(keyDef, key));
                delete(keyDef, key);
                success = true;
                return null;
            }
            success = true;
            return clazz.cast(value);
        } finally {
            recordOp("get", start, success);
        }
    }

    // ==================== Hash 操作 ====================

    /**
     * Hash 写入单个字段（{@code HSET}）。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param hashKey Hash 中的字段名
     * @param value   字段值
     */
    public void put(RedisKeyDefinition keyDef, String key, String hashKey, Object value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForHash().put(buildFullKey(keyDef, key), hashKey, value);
            success = true;
        } finally {
            recordOp("hset", start, success);
        }
    }

    /**
     * Hash 读取单个字段（{@code HGET}）。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param hashKey Hash 中的字段名
     * @return 字段值，不存在时返回 {@code null}
     */
    public Object get(RedisKeyDefinition keyDef, String key, String hashKey) {
        return redisTemplate.opsForHash().get(buildFullKey(keyDef, key), hashKey);
    }

    /**
     * Hash 删除字段（{@code HDEL}）。
     *
     * @param keyDef   Key 定义
     * @param key      Key 后缀
     * @param hashKeys 要删除的字段名
     * @return 成功删除的字段数量
     */
    public Long hashDelete(RedisKeyDefinition keyDef, String key, Object... hashKeys) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long deleted = redisTemplate.opsForHash().delete(buildFullKey(keyDef, key), hashKeys);
            success = true;
            return deleted;
        } finally {
            recordOp("hdel", start, success);
        }
    }

    /**
     * Hash 判断字段是否存在（{@code HEXISTS}）。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param hashKey Hash 中的字段名
     * @return {@code true} 表示字段存在
     */
    public Boolean hasKey(RedisKeyDefinition keyDef, String key, String hashKey) {
        return redisTemplate.opsForHash().hasKey(buildFullKey(keyDef, key), hashKey);
    }

    /**
     * Hash 批量写入（{@code HMSET}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param map    要写入的字段-值映射
     */
    public void putAll(RedisKeyDefinition keyDef, String key, Map<String, String> map) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForHash().putAll(buildFullKey(keyDef, key), map);
            success = true;
        } finally {
            recordOp("hmset", start, success);
        }
    }

    /**
     * Hash 获取全部字段和值（{@code HGETALL}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 所有字段和值的映射
     */
    public Map<Object, Object> getHashEntries(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(buildFullKey(keyDef, key));
            success = true;
            return entries;
        } finally {
            recordOp("hgetall", start, success);
        }
    }

    // ==================== List 操作 ====================

    /**
     * List 左推入（{@code LPUSH}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要推入的元素
     * @return 推入后列表的长度
     */
    public Long leftPush(RedisKeyDefinition keyDef, String key, Object value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long size = redisTemplate.opsForList().leftPush(buildFullKey(keyDef, key), value);
            success = true;
            return size;
        } finally {
            recordOp("lpush", start, success);
        }
    }

    /**
     * List 右推入（{@code RPUSH}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要推入的元素
     * @return 推入后列表的长度
     */
    public Long rightPush(RedisKeyDefinition keyDef, String key, Object value) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long size = redisTemplate.opsForList().rightPush(buildFullKey(keyDef, key), value);
            success = true;
            return size;
        } finally {
            recordOp("rpush", start, success);
        }
    }

    /**
     * List 左弹出（{@code LPOP}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 弹出的元素，列表为空时返回 {@code null}
     */
    public Object leftPop(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object value = redisTemplate.opsForList().leftPop(buildFullKey(keyDef, key));
            success = true;
            return value;
        } finally {
            recordOp("lpop", start, success);
        }
    }

    /**
     * List 右弹出（{@code RPOP}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 弹出的元素，列表为空时返回 {@code null}
     */
    public Object rightPop(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Object value = redisTemplate.opsForList().rightPop(buildFullKey(keyDef, key));
            success = true;
            return value;
        } finally {
            recordOp("rpop", start, success);
        }
    }

    /**
     * List 获取指定范围的元素（{@code LRANGE}），{@code end = -1} 表示到最后一个元素。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param start  起始索引（0-based）
     * @param end    结束索引
     * @return 范围内的元素列表
     */
    public List<Object> listRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            List<Object> range = redisTemplate.opsForList().range(buildFullKey(keyDef, key), start, end);
            success = true;
            return range;
        } finally {
            recordOp("lrange", startNanos, success);
        }
    }

    /**
     * List 获取长度。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 列表长度，Key 不存在时返回 {@code 0}
     */
    public Long listSize(RedisKeyDefinition keyDef, String key) {
        return redisTemplate.opsForList().size(buildFullKey(keyDef, key));
    }

    /**
     * List 裁剪，只保留指定范围内的元素（{@code LTRIM}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param start  起始索引（0-based）
     * @param end    结束索引
     */
    public void listTrim(RedisKeyDefinition keyDef, String key, long start, long end) {
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            redisTemplate.opsForList().trim(buildFullKey(keyDef, key), start, end);
            success = true;
        } finally {
            recordOp("ltrim", startNanos, success);
        }
    }

    // ==================== Set 操作 ====================

    /**
     * Set 添加元素（{@code SADD}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param values 要添加的元素
     * @return 成功添加的元素数量
     */
    public Long setAdd(RedisKeyDefinition keyDef, String key, Object... values) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long added = redisTemplate.opsForSet().add(buildFullKey(keyDef, key), values);
            success = true;
            return added;
        } finally {
            recordOp("sadd", start, success);
        }
    }

    /**
     * Set 移除元素（{@code SREM}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param values 要移除的元素
     * @return 成功移除的元素数量
     */
    public Long setRemove(RedisKeyDefinition keyDef, String key, Object... values) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long removed = redisTemplate.opsForSet().remove(buildFullKey(keyDef, key), values);
            success = true;
            return removed;
        } finally {
            recordOp("srem", start, success);
        }
    }

    /**
     * Set 获取所有元素（{@code SMEMBERS}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 集合中的所有元素
     */
    public Set<Object> setMembers(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Set<Object> members = redisTemplate.opsForSet().members(buildFullKey(keyDef, key));
            success = true;
            return members;
        } finally {
            recordOp("smembers", start, success);
        }
    }

    /**
     * Set 判断元素是否存在（{@code SISMEMBER}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要检查的元素
     * @return {@code true} 表示元素存在
     */
    public Boolean setIsMember(RedisKeyDefinition keyDef, String key, Object value) {
        return redisTemplate.opsForSet().isMember(buildFullKey(keyDef, key), value);
    }

    /**
     * Set 获取元素数量（{@code SCARD}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 集合中的元素数量
     */
    public Long setSize(RedisKeyDefinition keyDef, String key) {
        return redisTemplate.opsForSet().size(buildFullKey(keyDef, key));
    }

    // ==================== Sorted Set (ZSet) 操作 ====================

    /**
     * ZSet 添加元素（{@code ZADD}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  要添加的元素
     * @param score  分数
     * @return {@code true} 表示新元素添加成功
     */
    public Boolean zSetAdd(RedisKeyDefinition keyDef, String key, Object value, double score) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Boolean added = redisTemplate.opsForZSet().add(buildFullKey(keyDef, key), value, score);
            success = true;
            return added;
        } finally {
            recordOp("zadd", start, success);
        }
    }

    /**
     * ZSet 批量添加元素。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param tuples 元素和分数的集合
     * @return 成功添加的元素数量
     */
    public Long zSetAdd(RedisKeyDefinition keyDef, String key, Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<Object>> tuples) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long added = redisTemplate.opsForZSet().add(buildFullKey(keyDef, key), tuples);
            success = true;
            return added;
        } finally {
            recordOp("zadd", start, success);
        }
    }

    /**
     * ZSet 移除元素（{@code ZREM}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param values 要移除的元素
     * @return 成功移除的元素数量
     */
    public Long zSetRemove(RedisKeyDefinition keyDef, String key, Object... values) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long removed = redisTemplate.opsForZSet().remove(buildFullKey(keyDef, key), values);
            success = true;
            return removed;
        } finally {
            recordOp("zrem", start, success);
        }
    }

    /**
     * ZSet 按排名范围获取，升序（{@code ZRANGE}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param start  起始排名（0-based）
     * @param end    结束排名
     * @return 范围内的元素集合
     */
    public Set<Object> zSetRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            Set<Object> range = redisTemplate.opsForZSet().range(buildFullKey(keyDef, key), start, end);
            success = true;
            return range;
        } finally {
            recordOp("zrange", startNanos, success);
        }
    }

    /**
     * ZSet 按排名范围获取，降序（{@code ZREVRANGE}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param start  起始排名（0-based）
     * @param end    结束排名
     * @return 范围内的元素集合（按分数从高到低）
     */
    public Set<Object> zSetReverseRange(RedisKeyDefinition keyDef, String key, long start, long end) {
        long startNanos = System.nanoTime();
        boolean success = false;
        try {
            Set<Object> range = redisTemplate.opsForZSet().reverseRange(buildFullKey(keyDef, key), start, end);
            success = true;
            return range;
        } finally {
            recordOp("zrevrange", startNanos, success);
        }
    }

    /**
     * ZSet 按分数范围获取（{@code ZRANGEBYSCORE}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param min    最小分数
     * @param max    最大分数
     * @return 分数范围内的元素集合
     */
    public Set<Object> zSetRangeByScore(RedisKeyDefinition keyDef, String key, double min, double max) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Set<Object> range = redisTemplate.opsForZSet().rangeByScore(buildFullKey(keyDef, key), min, max);
            success = true;
            return range;
        } finally {
            recordOp("zrangebyscore", start, success);
        }
    }

    /**
     * ZSet 获取元素分数（{@code ZSCORE}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  目标元素
     * @return 元素的分数，元素不存在时返回 {@code null}
     */
    public Double zSetScore(RedisKeyDefinition keyDef, String key, Object value) {
        return redisTemplate.opsForZSet().score(buildFullKey(keyDef, key), value);
    }

    /**
     * ZSet 获取元素排名，升序（{@code ZRANK}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param value  目标元素
     * @return 排名（0-based），元素不存在时返回 {@code null}
     */
    public Long zSetRank(RedisKeyDefinition keyDef, String key, Object value) {
        return redisTemplate.opsForZSet().rank(buildFullKey(keyDef, key), value);
    }

    /**
     * ZSet 获取元素数量（{@code ZCARD}）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 有序集合中的元素数量
     */
    public Long zSetSize(RedisKeyDefinition keyDef, String key) {
        return redisTemplate.opsForZSet().size(buildFullKey(keyDef, key));
    }

    // ==================== 通用操作 ====================

    /**
     * 删除 Key。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return {@code true} 表示删除成功，{@code false} 表示 Key 不存在
     */
    public Boolean delete(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Boolean deleted = redisTemplate.delete(buildFullKey(keyDef, key));
            success = true;
            return deleted;
        } finally {
            recordOp("delete", start, success);
        }
    }

    /**
     * 批量删除 Key。
     *
     * @param keyDef Key 定义
     * @param keys   Key 后缀集合
     * @return 成功删除的 Key 数量
     */
    public Long delete(RedisKeyDefinition keyDef, Collection<String> keys) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long deleted = redisTemplate.delete(keys.stream().map(key -> buildFullKey(keyDef, key)).toList());
            success = true;
            return deleted;
        } finally {
            recordOp("delete", start, success);
        }
    }

    /**
     * 设置过期时间，使用 {@link RedisKeyDefinition#getTimeout()} 定义的超时时间（分钟）。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return {@code true} 表示设置成功；keyDef 超时为 {@code null}（永不过期）时返回 {@code false} 且不调用 Redis
     */
    public Boolean expire(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long timeout = keyDef.getTimeout();
            Boolean expired = Boolean.FALSE;
            if (timeout != null) {
                expired = redisTemplate.expire(buildFullKey(keyDef, key), toDuration(timeout, TimeUnit.MINUTES));
            }
            success = true;
            return expired;
        } finally {
            recordOp("expire", start, success);
        }
    }

    /**
     * 设置过期时间，自定义超时时间和单位。
     *
     * @param keyDef  Key 定义
     * @param key     Key 后缀
     * @param timeout 超时时间
     * @param unit    超时时间单位
     * @return {@code true} 表示设置成功
     */
    public Boolean expire(RedisKeyDefinition keyDef, String key, long timeout, TimeUnit unit) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Boolean expired = redisTemplate.expire(buildFullKey(keyDef, key), toDuration(timeout, unit));
            success = true;
            return expired;
        } finally {
            recordOp("expire", start, success);
        }
    }

    /**
     * 设置绝对过期时间。
     *
     * @param keyDef   Key 定义
     * @param key      Key 后缀
     * @param expireAt 过期的绝对时间
     * @return {@code true} 表示设置成功
     */
    public Boolean expireAt(RedisKeyDefinition keyDef, String key, java.time.Instant expireAt) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Boolean expired = redisTemplate.expireAt(buildFullKey(keyDef, key), expireAt);
            success = true;
            return expired;
        } finally {
            recordOp("expireat", start, success);
        }
    }

    /**
     * 自增 {@code +1}。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 自增后的值
     */
    public Long increment(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long value = redisTemplate.opsForValue().increment(buildFullKey(keyDef, key));
            success = true;
            return value;
        } finally {
            recordOp("incr", start, success);
        }
    }

    /**
     * 自增指定步长。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @param delta  步长值（可为负数实现递减）
     * @return 自增后的值
     */
    public Long increment(RedisKeyDefinition keyDef, String key, long delta) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long value = redisTemplate.opsForValue().increment(buildFullKey(keyDef, key), delta);
            success = true;
            return value;
        } finally {
            recordOp("incr", start, success);
        }
    }

    /**
     * 自减 {@code -1}。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return 自减后的值
     */
    public Long decrement(RedisKeyDefinition keyDef, String key) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            Long value = redisTemplate.opsForValue().decrement(buildFullKey(keyDef, key));
            success = true;
            return value;
        } finally {
            recordOp("decr", start, success);
        }
    }

    /**
     * 判断 Key 是否存在。
     *
     * @param keyDef Key 定义
     * @param key    Key 后缀
     * @return {@code true} 表示 Key 存在
     */
    public Boolean hasKey(RedisKeyDefinition keyDef, String key) {
        return redisTemplate.hasKey(buildFullKey(keyDef, key));
    }

}
