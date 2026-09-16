# redis-lock — Redis 锁模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+ / Redisson 4.7.0

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速验证](#快速验证)
- [功能详解](#功能详解)
    - [executeWithLock 自动加解锁](#executewithlock-自动加解锁)
    - [executeWithTryLock 带等待尝试](#executewithtrylock-带等待尝试)
    - [getLock 原始 RLock](#getlock-原始-rlock)
- [配置属性一览](#配置属性一览)
- [从旧锁迁移](#从旧锁迁移)
- [常见问题](#常见问题)

---

## 模块简介

`lightboot-redis-lock` 是基于 [Redisson](https://redisson.org/) 的可重入分布式锁模块，独立于 `lightboot-redis`
，按需引入。

| 能力                  | 说明                                              | 默认状态                  |
|---------------------|-------------------------------------------------|-----------------------|
| 可重入锁                | 同一线程可多次获取同一把锁（按 holdCount 计数）                   | 开启                    |
| 看门狗自动续期             | 默认 30s 租期、每约 10s 自动续期一次，长任务不会因过期丢锁              | 始终开启（封装不暴露 leaseTime） |
| Redisson 客户端        | 复用 `spring.data.redis` 配置，自动装配 `RedissonClient` | 开启                    |
| 直接使用 Redisson 全 API | 可注入 `RedissonClient` 使用公平锁/读写锁/信号量等             | 开启                    |

> **与旧锁的区别**：旧的简易锁 `RedisDistributedLock`（SET NX EX）**不支持重入、无看门狗续期**，长任务超时后锁丢失、临界区可能并发；本模块用
> Redisson 彻底解决。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-redis-lock</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

引入后自动生效，无需 `@Import` 或 `@ComponentScan`。Redisson 客户端**复用 `spring.data.redis` 配置**，无需额外定义
`redisson.*` 连接块：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      password: your_password
      # 集群 / 哨兵配置也会被自动识别
```

---

## 快速验证

### 1. 配置 Redis 连接

参考「引入方式」中的 `spring.data.redis` 配置。确保 `light-boot.redis.redisson.enabled` 未设为 `false`（默认 true）。

### 2. 注入并使用锁

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final RedissonDistributedLock distributedLock;

    public String createOrder(Long userId) {
        // 推荐：executeWithLock 自动加锁 + 解锁（看门狗续期）
        return distributedLock.executeWithLock("lock:order:create:" + userId,
                () -> doCreateOrder(userId));
    }

    private String doCreateOrder(Long userId) {
        // 临界区业务逻辑
        return "ORD-" + System.currentTimeMillis();
    }
}
```

### 3. 启动应用验证

```bash
# 控制台应无报错，Redis 连接成功
# 使用 redis-cli 观察锁 Key（带全局前缀，如 light-boot:lock:order:create:1）
redis-cli keys "light-boot:lock:*"
```

---

## 功能详解

### executeWithLock 自动加解锁

最推荐的方式——自动 `lock()` + try-finally `unlock()`，杜绝忘解锁，启用看门狗自动续期。

```java
@Resource
private RedissonDistributedLock distributedLock;

// 带返回值
String result = distributedLock.executeWithLock("order:123", () -> {
    return processOrder();   // 业务逻辑
});

// 无返回值
distributedLock.executeWithLock("order:123", () -> {
    sendNotification();      // 业务逻辑
});
```

**特点：**

- supplier/runnable 抛出的异常会在解锁后**原样向上抛**，不吞异常。
- 看门狗自动续期，长任务安全（不会因过期丢锁）。
- 可重入：同一线程嵌套调用同一 Key 的锁不会死锁。

### executeWithTryLock 带等待尝试

需要「最多等待一段时间抢锁、抢不到降级」时使用。**封装不暴露 `leaseTime`，所有加锁均启用看门狗自动续期。**

```java
// 带返回值：最多等 5s 抢锁并执行；抢锁失败返回 null
String result = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS,
        () -> processOrder());
if (result == null) {
    throw new ApiException("操作太频繁，请稍后再试");
}

// 无返回值：返回 true=抢到锁并执行，false=未抢到（lambda 体须为 void 兼容）
boolean locked = distributedLock.executeWithTryLock("order:123", 5, TimeUnit.SECONDS,
        () -> { processOrder(); });
if (!locked) {
    throw new ApiException("操作太频繁，请稍后再试");
}
```

| 方法签名                                                   | 说明                                           |
|--------------------------------------------------------|----------------------------------------------|
| `executeWithTryLock(key, waitTime, unit, Supplier<T>)` | 最多等 `waitTime` 抢锁并执行 supplier；失败/中断返回 `null` |
| `executeWithTryLock(key, waitTime, unit, Runnable)`    | 同上，返回 `boolean`：抢到执行 `true`，否则 `false`       |

> 被中断（`InterruptedException`）时恢复中断标志后返回 `null` / `false`。看门狗常开，长任务安全。
>
> 另注：`Supplier` / `Runnable` 两个重载由 lambda 体在编译期解析——`() -> processOrder()`（体产生返回值）匹配
> `Supplier` 重载，其结果不能赋给 `boolean`；需走 `Runnable` 重载时请用 void 兼容写法 `() -> { processOrder(); }`。
>
> 如需固定租约（到期自动释放、关闭看门狗），请 `getLock(key)` 后调用 Redisson 原生 `tryLock(waitTime, leaseTime, unit)`
> 自行管理。

### getLock 原始 RLock

需要 Redisson 全部能力（如查询 `holdCount`、公平锁、读写锁）时，直接获取 `RLock`。

```java
// 获取原始 RLock，自行 lock/unlock
RLock lock = distributedLock.getLock("order:123");
lock.lock();              // 启用看门狗
try {
    // 临界区
} finally {
    lock.unlock();
}

// 通过 RedisKeyDefinition 构建 Key（自动加全局前缀；第二参为 String 后缀）
RLock lock2 = distributedLock.getLock(OrderRedisKey.CREATE_LOCK, String.valueOf(userId));

// 查询当前线程持有次数
int holdCount = lock.getHoldCount();
```

> 也可以直接注入 `RedissonClient` 使用 Redisson 全部 API（公平锁 `getFairLock`、读写锁 `getReadWriteLock`、信号量
`getSemaphore` 等），此时建议设 `light-boot.redis.redisson.lock-enabled=false`，只装配客户端不要锁封装。

---

## 配置属性一览

```yaml
light-boot:
  redis:
    redisson:
      enabled: true            # 是否启用 Redisson 自动装配（RedissonClient + RedissonDistributedLock）
      lock-enabled: true       # 是否装配 RedissonDistributedLock
```

| 属性                                 | 类型        | 默认值    | 说明                                                                |
|------------------------------------|-----------|--------|-------------------------------------------------------------------|
| `light-boot.redis.redisson.enabled`      | `boolean` | `true` | 模块总开关；`false` 时即使引入模块也不创建 `RedissonClient`                        |
| `light-boot.redis.redisson.lock-enabled` | `boolean` | `true` | 锁封装开关；`false` 时仅保留 `RedissonClient`，不装配 `RedissonDistributedLock` |

> Redisson 连接参数复用 Spring Boot 标准 `spring.data.redis.*`
> （host/port/password/database/sentinel/cluster），支持单机、哨兵、集群三种模式。

---

## 从旧锁迁移

v1.0.0 删除了旧简易锁 `RedisDistributedLock`（SET NX EX），统一迁移到本模块的 `RedissonDistributedLock`。

### 1. 调整依赖

```xml
<!-- 移除对旧锁的间接依赖（lightboot-redis 已不再含锁） -->
<!-- 引入新模块 -->
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-redis-lock</artifactId>
</dependency>
```

### 2. 改注入

```java
// 旧（已删除）
// @Autowired
// private RedisDistributedLock distributedLock;

// 新
@Resource
private RedissonDistributedLock distributedLock;   // 包：cn.nextdev.lightboot.redis.lock.redisson
```

### 3. 改 API 调用

| 旧 API（已删除）                                                      | 新 API                                                                            |
|-----------------------------------------------------------------|----------------------------------------------------------------------------------|
| `tryLock(key) → requestId` + `unlock(key, requestId)`           | `executeWithLock(key, supplier)`（推荐）                                             |
| `tryLock(keyDef, suffix)` + `unlock(keyDef, suffix, requestId)` | `executeWithLock(keyDef.buildKey(suffix), supplier)` 或 `getLock(keyDef, suffix)` |
| `tryLockWithRetry(key, requestId, expire, retries, interval)`   | `executeWithTryLock(key, waitTime, unit, supplier)`（Redisson 内部排队等待，看门狗续期）       |

**迁移示例：**

```java
// ===== 旧写法（已删除） =====
// String lockId = distributedLock.tryLock("lock:order:1");
// if (lockId == null) { throw new ApiException("获取锁失败"); }
// try {
//     doBusiness();
// } finally {
//     distributedLock.unlock("lock:order:1", lockId);
// }

// ===== 新写法（推荐 executeWithLock） =====
distributedLock.executeWithLock("lock:order:1", () -> {
    doBusiness();
});

// ===== 新写法（带等待 executeWithTryLock） =====
boolean ok = distributedLock.executeWithTryLock("lock:order:1", 5, TimeUnit.SECONDS,
        () -> doBusiness());
if (!ok) {
    throw new ApiException("获取锁失败");
}
```

> **关键变化**：新锁**不再返回 requestId**，解锁通过 Key 完成（内部 `unlockQuietly` 校验当前线程持锁才解）。封装不暴露
`leaseTime`，看门狗常开。可重入 + 看门狗是核心增益。

---

## 常见问题

### 锁获取不到怎么办？

1. 确认 `light-boot.redis.redisson.enabled` 与 `light-boot.redis.redisson.lock-enabled` 未设为 `false`。
2. `executeWithTryLock` 的 `waitTime` 是否过短？适当调大等待时间。
3. 是否有其他请求/线程持锁未释放？用 redis-cli 观察锁 Key。

### 看门狗为什么没生效？

本封装的 `executeWithLock` / `executeWithTryLock` / `getLock(key).lock()` 均以 `leaseTime = -1` 抢锁，**始终启用看门狗**
（默认 30s 租期，每约 10s 自动续期一次）。若你通过 `getLock(key)` 拿到原始 `RLock` 后自行调用 Redisson 原生 `tryLock(waitTime, leaseTime, unit)`
指定了租约，则看门狗会被关闭，锁在 leaseTime 到期后自动释放——此时需改用不指定租约的加锁方式。

### unlock 抛 IllegalMonitorStateException？

框架的 `unlock(key)` 内部使用 `unlockQuietly`，仅当 `lock.isHeldByCurrentThread()` 为 true 时才解锁，正常使用不会抛此异常。若直接使用
`getLock(key).unlock()` 则需自行确保当前线程持锁。

### 如何只使用 Redisson 客户端，不要锁封装？

```yaml
light-boot:
  redis:
    redisson:
      lock-enabled: false   # 仅创建 RedissonClient
```

然后直接注入 `RedissonClient` 使用公平锁、读写锁、信号量等全部 Redisson 能力。

### 如何自定义 Redisson 连接池/Socket 参数？

直接注册自己的 `RedissonClient` Bean：

```java
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    // 自定义连接池、超时等参数
    config.useSingleServer()
          .setAddress("redis://localhost:6379")
          .setConnectionPoolSize(64)
          .setConnectionMinimumIdleSize(24);
    return Redisson.create(config);
}
```

框架的 `@ConditionalOnMissingBean` 会自动让位，使用自定义客户端。

### 如何关闭整个 Redisson 模块？

```yaml
light-boot:
  redis:
    redisson:
      enabled: false
```

引入了 `lightboot-redis-lock` 但不想装配时使用。
