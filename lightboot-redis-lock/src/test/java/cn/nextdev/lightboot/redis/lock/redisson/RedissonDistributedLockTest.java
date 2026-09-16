package cn.nextdev.lightboot.redis.lock.redisson;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedissonDistributedLock 单测：验证可重入锁委托、异常不吞、仅持锁时解锁。
 */
@ExtendWith(MockitoExtension.class)
class RedissonDistributedLockTest {

    private static final String FULL_KEY = "light-boot:order:123";

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private RedissonDistributedLock distributedLock;

    @BeforeEach
    void setUp() {
        distributedLock = new RedissonDistributedLock(redissonClient, "light-boot:");
    }

    /**
     * executeWithLock 加锁→执行业务→解锁的完整流程。
     */
    @Test
    void executeWithLock_locksExecutesAndUnlocks() {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicReference<String> ran = new AtomicReference<>("no");

        String result = distributedLock.executeWithLock("order:123", () -> {
            ran.set("yes");
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(ran.get()).isEqualTo("yes");
        verify(lock, times(1)).lock();
        verify(lock, times(1)).unlock();
    }

    /**
     * supplier 抛异常时异常原样上抛，且仍执行解锁。
     */
    @Test
    void executeWithLock_propagatesSupplierExceptionAndStillUnlocks() {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RuntimeException boom = new RuntimeException("业务异常");

        assertThatThrownBy(() -> distributedLock.executeWithLock("order:123", () -> {
            throw boom;
        })).isSameAs(boom);

        verify(lock, times(1)).lock();
        verify(lock, times(1)).unlock(); // 异常后仍解锁
    }

    /**
     * executeWithLock 的 Runnable 重载正常执行。
     */
    @Test
    void executeWithLock_runnableOverloadExecutesNormally() {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicReference<String> ran = new AtomicReference<>("no");

        distributedLock.executeWithLock("order:123", () -> ran.set("yes"));

        assertThat(ran.get()).isEqualTo("yes");
        verify(lock, times(1)).lock();
        verify(lock, times(1)).unlock();
    }

    /**
     * unlock 仅当当前线程持锁时才执行解锁。
     */
    @Test
    void unlock_onlyUnlocksWhenHeldByCurrentThread() {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        distributedLock.unlock("order:123");

        verify(lock, never()).unlock();
    }

    /**
     * getLock 返回带全局前缀的锁。
     */
    @Test
    void getLock_returnsLockWithGlobalPrefix() {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        RLock got = distributedLock.getLock("order:123");

        assertThat(got).isSameAs(lock);
        verify(redissonClient).getLock("light-boot:order:123");
    }

    /**
     * globalPrefix 为 null 时按空串处理，锁 key 不带全局前缀。
     */
    @Test
    void globalPrefix_treatedAsEmptyWhenNull() {
        RedissonDistributedLock noPrefix = new RedissonDistributedLock(redissonClient, null);
        when(redissonClient.getLock("order:123")).thenReturn(lock);

        RLock got = noPrefix.getLock("order:123");

        assertThat(got).isSameAs(lock);
        verify(redissonClient).getLock("order:123");
    }

    /**
     * executeWithTryLock 抢锁成功时执行 supplier 并返回结果，且使用看门狗（leaseTime = -1）。
     */
    @Test
    void executeWithTryLock_runsSupplierWhenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        Integer result = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS, () -> 42);

        assertThat(result).isEqualTo(42);
        verify(lock).unlock();
    }

    /**
     * executeWithTryLock 抢锁失败时返回 null，不执行 supplier，不解锁。
     */
    @Test
    void executeWithTryLock_returnsNullWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(false);
        @SuppressWarnings("unchecked")
        Supplier<String> supplier = mock(Supplier.class);

        String result = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS, supplier);

        assertThat(result).isNull();
        verify(supplier, never()).get();
        verify(lock, never()).unlock();
    }

    /**
     * executeWithTryLock 的 Runnable 重载抢锁成功时执行 runnable、解锁，并返回 true。
     */
    @Test
    void executeWithTryLock_runnableOverloadRunsWhenLockAcquired() throws InterruptedException {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Runnable runnable = mock(Runnable.class);

        boolean ok = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS, runnable);

        assertThat(ok).isTrue();
        verify(runnable, times(1)).run();
        verify(lock, times(1)).unlock();
    }

    /**
     * executeWithTryLock 的 Runnable 重载抢锁失败时返回 false，不执行 runnable，不解锁。
     */
    @Test
    void executeWithTryLock_runnableOverloadReturnsFalseWhenLockNotAcquired() throws InterruptedException {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(false);
        Runnable runnable = mock(Runnable.class);

        boolean ok = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS, runnable);

        assertThat(ok).isFalse();
        verify(runnable, never()).run();
        verify(lock, never()).unlock();
    }

    /**
     * supplier 抛异常时异常原样上抛，且仍执行解锁（finally 释放）。
     */
    @Test
    void executeWithTryLock_propagatesSupplierExceptionAndStillUnlocks() throws InterruptedException {
        when(redissonClient.getLock(FULL_KEY)).thenReturn(lock);
        when(lock.tryLock(5L, -1L, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RuntimeException boom = new RuntimeException("业务异常");

        assertThatThrownBy(() -> distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(lock, times(1)).unlock(); // 异常后仍解锁
    }
}
