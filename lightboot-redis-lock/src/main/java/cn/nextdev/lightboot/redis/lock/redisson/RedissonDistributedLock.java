package cn.nextdev.lightboot.redis.lock.redisson;

import cn.nextdev.lightboot.redis.key.RedisKeyDefinition;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的分布式锁封装。
 *
 * <p>相对简易锁（SET NX EX）的核心增益：<b>可重入</b>（同一线程/客户端可多次获取）与
 * <b>看门狗自动续期</b>（默认 30s 租期，看门狗每约租期 1/3 即约 10s 自动续期一次，长任务不会因过期丢锁）。
 *
 * @see RedissonClient
 */
public class RedissonDistributedLock {

    private final RedissonClient redissonClient;
    private final String globalPrefix;

    /**
     * 创建 Redisson 锁封装实例。
     *
     * @param redissonClient Redisson 客户端
     * @param globalPrefix   全局 Key 前缀，通常来源于 {@code RedisProperties.keyPrefix}
     */
    public RedissonDistributedLock(RedissonClient redissonClient, String globalPrefix) {
        this.redissonClient = redissonClient;
        this.globalPrefix = globalPrefix != null ? globalPrefix : "";
    }

    /**
     * 构建带全局前缀的完整 Key。
     *
     * @param key 原始 Key（不含前缀）
     * @return {@code globalPrefix + key}
     */
    private String buildKey(String key) {
        return globalPrefix + key;
    }

    /**
     * 获取可重入锁（看门狗自动续期，无需指定过期时间）。
     *
     * <p>调用方获得 {@link RLock} 后需自行 {@code lock()} / {@code unlock()}；
     * 便捷场景建议使用 {@link #executeWithLock}。
     *
     * @param key 锁 Key（原始，自动加全局前缀）
     * @return Redisson 锁对象
     */
    public RLock getLock(String key) {
        return redissonClient.getLock(buildKey(key));
    }

    /**
     * 获取可重入锁（{@link RedisKeyDefinition} 构建 Key）。
     *
     * @param keyDef Key 定义
     * @param suffix Key 后缀
     * @return Redisson 锁对象
     */
    public RLock getLock(RedisKeyDefinition keyDef, String suffix) {
        return getLock(keyDef.buildKey(suffix));
    }

    /**
     * 一次性执行：自动加锁 + try-finally 解锁，业务返回值由 supplier 提供。
     *
     * <p>使用看门狗续期（不指定租约）。supplier 抛出的异常会在解锁后原样向上抛，不吞异常。
     *
     * @param key      锁 Key
     * @param supplier 受锁保护的业务逻辑
     * @param <T>      返回类型
     * @return supplier 的返回值
     */
    public <T> T executeWithLock(String key, Supplier<T> supplier) {
        RLock lock = getLock(key);
        lock.lock();
        try {
            return supplier.get();
        } finally {
            unlockQuietly(lock);
        }
    }

    /**
     * 一次性执行（无返回值）。
     *
     * @param key      锁 Key
     * @param runnable 受锁保护的业务逻辑
     */
    public void executeWithLock(String key, Runnable runnable) {
        RLock lock = getLock(key);
        lock.lock();
        try {
            runnable.run();
        } finally {
            unlockQuietly(lock);
        }
    }

    /**
     * 尝试加锁并一次性执行（看门狗自动续期，不指定租约）。
     *
     * <p>始终以 {@code leaseTime = -1} 抢锁，由 Redisson 看门狗自动续期，长任务不会因过期丢锁。
     * 等待超时未抢到锁时返回 null（supplier 不执行）；等待期间当前线程被中断时，恢复中断标记并返回 null。
     *
     * @param key      锁 Key
     * @param waitTime 最长等待时间
     * @param unit     时间单位
     * @param supplier 受锁保护的业务逻辑
     * @param <T>      返回类型
     * @return 抢锁成功返回 supplier 的返回值；抢锁失败或被中断返回 null
     */
    public <T> T executeWithTryLock(String key, long waitTime, TimeUnit unit, Supplier<T> supplier) {
        RLock lock = getLock(key);
        boolean locked;
        try {
            locked = lock.tryLock(waitTime, -1L, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!locked) {
            return null;
        }
        try {
            return supplier.get();
        } finally {
            unlockQuietly(lock);
        }
    }

    /**
     * 尝试加锁并一次性执行（无返回值，看门狗续期）。
     *
     * @param key      锁 Key
     * @param waitTime 最长等待时间
     * @param unit     时间单位
     * @param runnable 受锁保护的业务逻辑
     * @return 抢锁成功返回 true；失败或被中断返回 false
     */
    public boolean executeWithTryLock(String key, long waitTime, TimeUnit unit, Runnable runnable) {
        RLock lock = getLock(key);
        boolean locked;
        try {
            locked = lock.tryLock(waitTime, -1L, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!locked) {
            return false;
        }
        try {
            runnable.run();
            return true;
        } finally {
            unlockQuietly(lock);
        }
    }

    /**
     * 解锁：仅当当前线程持有该锁时才实际解锁，否则静默忽略（不抛 {@code IllegalMonitorStateException}）。
     *
     * @param key 锁 Key
     */
    public void unlock(String key) {
        unlockQuietly(getLock(key));
    }

    /**
     * 安静解锁：仅当当前线程持有锁时才解锁，避免 {@code IllegalMonitorStateException}。
     *
     * @param lock 待解锁的锁对象
     */
    private void unlockQuietly(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

}
