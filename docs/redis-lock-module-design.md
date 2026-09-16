# redis-lock 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+ / Redisson 4.7.0

---

## 定位

可选的分布式锁模块 `lightboot-redis-lock`，基于 **Redisson** 提供**可重入 + 看门狗自动续期**的分布式锁，替代旧简易锁
`RedisDistributedLock`（SET NX EX + Lua 解锁）。引入依赖 + 配置 `spring.data.redis` 即生效。

> **背景（评估 P0#6）**：旧 `RedisDistributedLock` 不支持重入、无看门狗续期。长任务超过过期时间后锁会被自动释放，导致临界区可能并发执行——生产环境最高风险点。本模块用
> Redisson 的成熟锁实现彻底解决。

---

## 关键决策

1. **独立可选模块**：不污染 `lightboot-redis`，不引入则无 Redisson 依赖、无 `RedissonClient` 装配。
2. **复用 `spring.data.redis` 配置**：不引入独立的 `redisson.*` 连接块，由 `RedissonAutoConfiguration` 从 Spring Boot 标准
   `RedisProperties` 构造 Redisson `Config`。
3. **薄封装服务类 `RedissonDistributedLock`**：不把 Redisson 全 API 暴露到业务层，仅提供加锁/执行/解锁的常用语义。
4. **删除旧锁**（非废弃保留）：`RedisDistributedLock` 及其连带清理已在 v1.0.0 完成。
5. **Redisson 4.7.0**：版本集中在 BOM（`lightboot-bom`）的 `<redisson.version>`，可一行升级。

### 非目标（YAGNI）

- 不封装公平锁、读写锁、信号量、闭锁等 Redisson 高级能力（消费者可直接注入 `RedissonClient` 自用）。
- 不暴露 Redisson 连接池/Socket 调优参数（沿用默认；个别场景消费者自行注册 `RedissonClient` 覆盖）。

---

## 模块结构

```text
lightboot-redis-lock/
└── src/main/
    ├── java/cn/nextdev/lightboot/redis/lock/redisson/
    │   ├── config/
    │   │   ├── RedissonAutoConfiguration.java   — 装配 RedissonClient（从 spring.data.redis 构造）+ RedissonDistributedLock
    │   │   └── RedissonProperties.java          — light-boot.redis.redisson.* 扩展项
    │   └── RedissonDistributedLock.java         — 薄封装锁服务
    └── resources/META-INF/
        ├── spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
        └── additional-spring-configuration-metadata.json
```

- **包名** `cn.nextdev.lightboot.redis.lock.redisson`，与 redis 模块同根 `cn.nextdev.lightboot.redis`，互不耦合。
- **依赖**：`lightboot-redis`（compile，复用 `RedisProperties` / `RedisKeyDefinition`）+ `redisson`（compile，版本由根
  BOM 管理）+ `lombok` / `configuration-processor`（optional）。

---

## 自动配置加载顺序

```text
RedisTemplateAutoConfiguration（redis 模块，提供 RedisProperties 与全局 Key 前缀）
    ↓
RedissonAutoConfiguration（@AutoConfigureAfter(RedisTemplateAutoConfiguration.class)）
    ├─ RedissonClient（@ConditionalOnMissingBean）
    └─ RedissonDistributedLock（@ConditionalOnMissingBean + @ConditionalOnProperty(lock-enabled)）
```

**装配条件：**

- `@ConditionalOnClass(org.redisson.Redisson.class)` — 类路径无 Redisson 时不装配，不污染下游。
- `@ConditionalOnProperty(light-boot.redis.redisson.enabled, matchIfMissing=true)` — 模块总开关。
- `RedissonClient`：`@ConditionalOnMissingBean` — 用户自定义时自动让位。
- `RedissonDistributedLock`：`@ConditionalOnMissingBean` +
  `@ConditionalOnProperty(light-boot.redis.redisson.lock-enabled, matchIfMissing=true)` — 锁封装可单独关闭。

---

## 核心设计

### RedissonClient — 连接复用策略

`RedissonAutoConfiguration` 注入 Spring Boot 已绑定的
`org.springframework.boot.data.redis.autoconfigure.DataRedisProperties`，按以下优先级构造 Redisson `Config`：

| spring.data.redis 配置  | Redisson Config        | 地址/库处理                                                                     |
|-----------------------|------------------------|---------------------------------------------------------------------------|
| `cluster.nodes`（非空）   | `useClusterServers()`  | 逐节点 `addNodeAddress`                                                      |
| `sentinel.master`（非空） | `useSentinelServers()` | `setMasterName` + 逐节点 `addSentinelAddress`；`database != 0` 时 `setDatabase` |
| 其他（默认）                | `useSingleServer()`    | `setAddress(host,port)` + `setDatabase`                                   |

**凭证处理：** 用户名与密码在 `Config` 级别统一设置（`setUsername` / `setPassword`，非空才设置），对单机、哨兵、集群三种模式同时生效，不在各模式配置上单独处理。

**地址转换：** `host:port` 自动补 scheme——`spring.data.redis.ssl=true` 时用 `rediss://`，否则 `redis://`（单机与集群/哨兵节点行为一致）；
节点已显式带 `redis://` 或 `rediss://` 时原样保留（显式优先，前后空白裁剪）。

> 连接池、Socket 等参数沿用 Redisson 默认值。如需自定义，可直接注册自己的 `RedissonClient` Bean，框架的
`@ConditionalOnMissingBean` 会自动让位。

### RedissonProperties — 最小配置集

前缀 `light-boot.redis.redisson`，**只控制开关，不重复定义连接参数**：

| 属性            | 类型        | 默认值    | 说明                                                             |
|---------------|-----------|--------|----------------------------------------------------------------|
| `enabled`     | `boolean` | `true` | 是否启用 Redisson 装配（`RedissonClient` + `RedissonDistributedLock`） |
| `lockEnabled` | `boolean` | `true` | 是否装配 `RedissonDistributedLock`；`false` 时仅保留 `RedissonClient`   |

### RedissonDistributedLock — 锁 API 设计

基于 Redisson `RLock`，天然支持**可重入**（同一线程可多次获取，按 holdCount 计数）与**看门狗自动续期**。本封装**不暴露 `leaseTime`
参数**，所有加锁方式均以 `leaseTime = -1` 抢锁，由 Redisson 看门狗自动续期（默认 30s 租期，每约 10s 即租期 1/3 续期一次）。

| 方法                                                     | 返回值       | 说明                                                            |
|--------------------------------------------------------|-----------|---------------------------------------------------------------|
| `executeWithLock(key, Supplier<T>)`                    | `T`       | 自动 `lock()` + try-finally `unlock()`，supplier 异常原样上抛。**业务首选** |
| `executeWithLock(key, Runnable)`                       | `void`    | 同上，无返回值                                                       |
| `executeWithTryLock(key, waitTime, unit, Supplier<T>)` | `T`       | 最多等待 `waitTime` 抢锁并执行；抢锁失败返回 `null`，被中断恢复中断标志后返回 `null`       |
| `executeWithTryLock(key, waitTime, unit, Runnable)`    | `boolean` | 同上，返回 `true`=抢到锁并执行，`false`=未抢到                               |
| `getLock(key)`                                         | `RLock`   | 获取原始 Redisson 锁对象（自行控制生命周期或查询 `holdCount`）                    |
| `getLock(keyDef, suffix)`                              | `RLock`   | 通过 `RedisKeyDefinition` 构建 Key                                |
| `unlock(key)`                                          | `void`    | 解锁（内部 `unlockQuietly`，仅当前线程持锁才解）                              |

**行为约定：**

- **看门狗常开**：`executeWithLock` / `executeWithTryLock` / `getLock(key).lock()` 均启用 Redisson 看门狗。如需固定租约、到期自动释放，请
  `getLock(key)` 后调用 Redisson 原生 `tryLock(waitTime, leaseTime, unit)`。
- **异常处理**：`executeWithLock` / `executeWithTryLock` 中 supplier 抛异常 → 解锁后原样向上抛，不吞异常。
- **中断处理**：`executeWithTryLock` 捕获 `InterruptedException` 时恢复中断标志（`Thread.currentThread().interrupt()`）后返回
  `null` / `false`。
- **安静解锁**：`unlockQuietly` 仅在 `lock.isHeldByCurrentThread()` 为 true 时才 `unlock()`，避免
  `IllegalMonitorStateException`。
- **Key 前缀**：复用 `RedisProperties.keyPrefix`（`light-boot.redis.key-prefix`，默认 `light-boot:`，为 null 时按空串处理），
  锁 Key 实际为 `{全局前缀}{原始 key}`。
- **线程安全**：`RedissonClient` 本身线程安全；本类仅持 final 的 client 与 globalPrefix，无状态，天然线程安全。

---

## Bean 注册清单

| Bean                      | 条件                                                                    |
|---------------------------|-----------------------------------------------------------------------|
| `RedissonClient`          | `@ConditionalOnMissingBean`，从 `spring.data.redis` 构造                  |
| `RedissonDistributedLock` | `@ConditionalOnMissingBean` + `light-boot.redis.redisson.lock-enabled=true` |

---

## 删除旧锁实现的破坏性说明

v1.0.0 已**删除**旧 `RedisDistributedLock`（非废弃保留），连带清理：

1. 删除 `lightboot-redis/.../lock/RedisDistributedLock.java`，整个 `lock/` 目录移除。
2. `RedisServiceAutoConfiguration` 移除 `redisDistributedLock(...)` Bean 方法及相关 import、Javadoc。
3. `RedisProperties.lockEnabled`（`light-boot.redis.lock-enabled`）随旧锁删除。
4. `RedisConstants.LOCK_DEFAULT_TIMEOUT` 删除。
5. `RedissonDistributedLock` 的配置属性 `lock-enabled` **迁移到** `light-boot.redis.redisson.lock-enabled`（语义不同，控制新锁）。

**破坏性影响**：现有业务若注入旧 `RedisDistributedLock`，升级后编译断裂，需改注入新模块 `RedissonDistributedLock`。1.0.0
未发布，可接受。README 与 usage.md 提供迁移说明。

---

## 版本选择理由

BOM 统一管理 `<redisson.version>4.7.0</redisson.version>`，与 Spring Boot 4.1 / Java 21 基线匹配，可一行升级。

---

## 依赖

| 依赖                                    | 必选/可选 | 作用                                                                             |
|---------------------------------------|-------|--------------------------------------------------------------------------------|
| `lightboot-redis`                | 必选    | 复用 `RedisProperties` / `RedisKeyDefinition` / `RedisTemplateAutoConfiguration` |
| `redisson`                            | 必选    | 分布式锁实现（版本由根 BOM 管理）                                                            |
| `spring-boot-configuration-processor` | 可选    | IDE 配置提示                                                                       |
| `lombok`                              | 可选    | 简化代码                                                                           |

---

## 配置属性

### light-boot.redis.redisson（模块配置）

| 属性             | 默认值    | 说明                                                  |
|----------------|--------|-----------------------------------------------------|
| `enabled`      | `true` | 模块总开关（`RedissonClient` + `RedissonDistributedLock`） |
| `lock-enabled` | `true` | 锁封装开关；`false` 时仅保留 `RedissonClient`                 |

> 连接参数复用 `spring.data.redis.*`，本属性不重复定义。

---

## 扩展点

| 扩展点                 | 方式                                                            |
|---------------------|---------------------------------------------------------------|
| 自定义 Redisson 客户端    | 注册自己的 `RedissonClient` Bean（`@ConditionalOnMissingBean` 自动让位） |
| 直接使用 Redisson 全 API | 注入 `RedissonClient`（公平锁、读写锁、信号量、闭锁等）                          |
| 通过 Key 定义加锁         | `getLock(RedisKeyDefinition, suffix)`                         |
| 仅用客户端不要锁封装          | `light-boot.redis.redisson.lock-enabled=false`                      |

---

## 注意事项

- **看门狗常开**：本封装不暴露 `leaseTime`，`executeWithLock` / `executeWithTryLock` / `getLock().lock()` 均以
  `leaseTime = -1` 抢锁，看门狗始终续期。需固定租约时通过 `getLock(key)` 调用 Redisson 原生
  `tryLock(waitTime, leaseTime, unit)` 自行管理。
- **可重入性范围**：可重入是同一线程/同一客户端维度，不是跨 JVM。
- **`unlockQuietly` 仅当前线程持锁才解锁**：避免重复解锁或误解他人锁时抛 `IllegalMonitorStateException`。
- **Key 前缀与 redis 模块一致**：锁 Key 会被加上 `light-boot.redis.key-prefix`，与 `RedisService` / `SequenceService` 同前缀。
- **测试覆盖**：`RedissonDistributedLockTest`（Mockito）覆盖加锁→执行→解锁流程、异常不吞、仅当前线程持锁才解锁、
  `tryLock` 失败返回 `null`/`false` 及 Key 前缀拼接；**中断恢复标志**（`InterruptedException` → 恢复中断标志后返回
  `null`/`false`）目前无单测覆盖。`RedissonAutoConfigurationTest` 覆盖地址 scheme 构建（含 TLS 与显式 scheme
  优先）。Redisson 真实连接行为依赖手测。
