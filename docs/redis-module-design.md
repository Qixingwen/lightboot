# redis 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+

---

## 定位

Redis 集成模块，提供 **统一操作 API**、**Key 接口化管理**、**Spring Cache 集成**、**序列号生成**、**安全序列化** 与 **可选读容错
**。引入依赖 + 配置连接信息即生效。

> 分布式锁已独立为 `lightboot-redis-lock`（基于 Redisson，可重入 + 看门狗）。本模块不再含锁实现。

---

## 模块结构

```text
cn.nextdev.lightboot.redis/
├── config/
│   ├── RedisTemplateAutoConfiguration.java    — RedisTemplate（JSON 序列化）+ Lettuce 超时/连接池定制器
│   ├── RedisCachingAutoConfiguration.java     — @EnableCaching 启用
│   ├── RedisCacheAutoConfiguration.java       — RedisCacheManager + CacheErrorHandler（经 CachingConfigurer 接入）
│   ├── RedisServiceAutoConfiguration.java     — RedisService / FaultTolerantRedisService / SequenceService
│   ├── RedisObservabilityAutoConfiguration.java — RedisMetricsRecorder（需 actuator）
│   ├── RedisProperties.java                   — 模块属性（light-boot.redis.*）
│   └── RedisCacheProperties.java              — 缓存属性（light-boot.cache.redis.*）
├── constant/
│   └── RedisConstants.java                    — 常量（Key 前缀 / 序列号格式）
├── exception/
│   └── SequenceGenerationException.java       — 序列号生成失败异常（继承 ApiException）
├── key/
│   └── RedisKeyDefinition.java                — Key 定义接口
├── serializer/
│   ├── RedisJsonSerializerFactory.java        — JSON 序列化工厂（类型白名单）
│   └── SequenceLongSerializer.java            — 序列号 Long 序列化器
├── service/
│   ├── RedisService.java                      — 统一 Redis 操作服务
│   ├── FaultTolerantRedisService.java         — 读容错实现（light-boot.redis.fault-tolerant=true）
│   └── SequenceService.java                   — 序列号生成服务
└── metrics/
    └── RedisMetricsRecorder.java              — Redis 操作耗时指标（light-boot.redis.operations）
```

**16 个 Java 文件** + `AutoConfiguration.imports`（注册 5 个自动配置类）。

---

## 自动配置加载顺序

```text
1. RedisTemplateAutoConfiguration — RedisTemplate + RedisJsonSerializerFactory + Lettuce 超时/连接池定制器
2. RedisCachingAutoConfiguration  — @EnableCaching（幂等开启 Spring Cache 注解支持）
3. RedisCacheAutoConfiguration    — RedisCacheManager + CacheErrorHandler（经 CachingConfigurer 接入）
4. RedisServiceAutoConfiguration  — RedisService（或 FaultTolerantRedisService）/ SequenceService
5. RedisObservabilityAutoConfiguration — RedisMetricsRecorder（需类路径存在 MeterRegistry）
```

---

## 核心设计

### RedisKeyDefinition — Key 定义接口

业务模块通过枚举实现，统一管理 Key 前缀和超时时间：

- `getPrefix()` — Key 前缀（如 `auth:sms:`）
- `getTimeout()` — 超时分钟数（`Long`，null 表示永不过期）
- `buildKey(String suffix)` — 默认方法，拼接完整 Key（prefix + suffix）

### RedisService — 统一操作 API

封装 Redis 五大数据结构操作 + 通用操作。所有方法以 `RedisKeyDefinition` + `suffix` 为参数，自动拼接全局前缀（
`light-boot.redis.key-prefix`）。

> **扩展访问：** `RedisService` 通过 `@Getter` 暴露了 `redisTemplate` 字段（`RedisTemplate<String, Object>`），需要直接调用未封装的高级
> Redis 命令时可通过 `redisService.getRedisTemplate()` 获取。

#### String 操作

| 方法                                                        | 说明                                                                |
|-----------------------------------------------------------|-------------------------------------------------------------------|
| `setString(keyDef, suffix, value)`                        | 设置字符串（使用 keyDef 的超时）                                              |
| `setString(keyDef, suffix, value, timeout, unit)`         | 设置字符串（自定义超时）                                                      |
| `setStringWithoutExpire(keyDef, suffix, value)`           | 设置字符串（永不过期）                                                       |
| `setObject(keyDef, suffix, object)`                       | 设置对象（JSON 序列化，使用 keyDef 的超时）                                      |
| `setObject(keyDef, suffix, object, timeout, unit)`        | 设置对象（自定义超时）                                                       |
| `setObjectWithoutExpire(keyDef, suffix, object)`          | 设置对象（永不过期）                                                        |
| `setStringIfAbsent(keyDef, suffix, value, timeout, unit)` | SET NX（不存在时设置）                                                    |
| `getString(keyDef, suffix)`                               | 获取字符串（值为非 String 对象时返回其 `toString()`，需 JSON 反序列化请用 `getObject`） |
| `getObject(keyDef, suffix)`                               | 获取对象                                                              |
| `getObject(keyDef, suffix, Class<T>)`                     | 获取对象（类型安全；类型不匹配时记 WARN、删除脏 Key、返回 `null`，不抛 `ClassCastException`） |

> 指标埋点：写操作与大 payload 读（`set` / `get` / `delete` / `incr` / `hgetall` / `smembers` / `zrange` 等）会经
> `RedisMetricsRecorder` 记录耗时到 `light-boot.redis.operations`（需 actuator，tag：`operation`、`result`）。轻量点查询
> `hasKey`（含 Hash 版）/ `setIsMember` / `zSetScore` / `zSetRank` / `setSize` / `listSize` / `zSetSize` / Hash `get` **不埋点**
> ——这类查询即使慢也是被大 key 拖累，信号会体现在写或重读上。

#### Hash 操作

| 方法                                        | 说明           |
|-------------------------------------------|--------------|
| `put(keyDef, suffix, hashKey, value)`     | 写入 Hash 字段   |
| `get(keyDef, suffix, hashKey)`            | 读取 Hash 字段   |
| `hashDelete(keyDef, suffix, hashKeys...)` | 删除 Hash 字段   |
| `hasKey(keyDef, suffix, hashKey)`         | Hash 字段是否存在  |
| `putAll(keyDef, suffix, map)`             | 批量写入 Hash    |
| `getHashEntries(keyDef, suffix)`          | 获取 Hash 所有字段 |

#### List 操作

| 方法                                      | 说明   |
|-----------------------------------------|------|
| `leftPush` / `rightPush`                | 入队   |
| `leftPop` / `rightPop`                  | 出队   |
| `listRange(keyDef, suffix, start, end)` | 范围查询 |
| `listSize(keyDef, suffix)`              | 列表长度（Key 不存在时返回 `0`） |
| `listTrim(keyDef, suffix, start, end)`  | 裁剪列表 |

#### Set 操作

| 方法                                   | 说明       |
|--------------------------------------|----------|
| `setAdd` / `setRemove`               | 添加/移除元素  |
| `setMembers(keyDef, suffix)`         | 获取所有元素   |
| `setIsMember(keyDef, suffix, value)` | 判断元素是否存在 |
| `setSize(keyDef, suffix)`            | 集合大小     |

#### ZSet 操作

| 方法                                           | 说明             |
|----------------------------------------------|----------------|
| `zSetAdd(keyDef, suffix, value, score)`      | 添加元素           |
| `zSetAdd(keyDef, suffix, Set<TypedTuple>)`   | 批量添加           |
| `zSetRemove(keyDef, suffix, values...)`      | 移除元素           |
| `zSetRange` / `zSetReverseRange`             | 按排名范围查询（正序/倒序） |
| `zSetRangeByScore(keyDef, suffix, min, max)` | 按分数范围查询        |
| `zSetScore(keyDef, suffix, value)`           | 获取元素分数         |
| `zSetRank(keyDef, suffix, value)`            | 获取元素排名         |
| `zSetSize(keyDef, suffix)`                   | 有序集合大小         |

#### 通用操作

| 方法                                      | 说明                |
|-----------------------------------------|-------------------|
| `delete(keyDef, suffix)`                | 删除 Key            |
| `delete(keyDef, Collection<suffix>)`    | 批量删除              |
| `expire(keyDef, suffix)`                | 使用 keyDef 的超时设置过期 |
| `expire(keyDef, suffix, timeout, unit)` | 自定义过期时间           |
| `expireAt(keyDef, suffix, Instant)`     | 设置过期时间点           |
| `increment(keyDef, suffix)`             | 自增 1              |
| `increment(keyDef, suffix, delta)`      | 自增指定值             |
| `decrement(keyDef, suffix)`             | 自减 1              |
| `hasKey(keyDef, suffix)`                | Key 是否存在          |

### FaultTolerantRedisService — 读容错

`light-boot.redis.fault-tolerant=true` 时，自动装配的 `RedisService` Bean 替换为 `FaultTolerantRedisService`。它**仅捕获
`DataAccessException`**（连接失败、超时等数据访问异常）并对**读操作降级**（返回 `null` / 空集合 / `false`，仅记 WARN）——
值反序列化失败等非数据访问异常仍上抛；**写操作仍抛异常**（避免静默吞掉写失败造成数据不一致）。默认 `false`。

| 降级读方法                                                                       | 降级返回值   |
|-----------------------------------------------------------------------------|---------|
| `getString` / `getObject`（含带 Class 重载）/ Hash `get`                          | `null`  |
| `getHashEntries` / `listRange` / `setMembers` / `zSet*Range*`               | 空集合     |
| `hasKey`（含 Hash）/ `setIsMember`                                             | `false` |
| `listSize` / `setSize` / `zSetScore` / `zSetRank` / `zSetSize` / `leftPop` / `rightPop` | `null`  |

> 写操作（`set*` / `put*` / `push` / `delete` / `expire` / `increment` / `decrement` 等）不在容错范围，仍抛异常。开启容错会掩盖
> Redis 故障，应在知晓代价后使用。
>
> ⚠️ `leftPop` / `rightPop` 是**破坏性读**（LPOP/RPOP）：降级返回 `null` 时 Redis 端可能已成功弹出元素。不容忍消息丢失的队列
> 消费场景请保持 `fault-tolerant=false`，让异常上抛触发上层重投递/补偿。

### SequenceService — 序列号生成

基于 Redis `INCR` 原子递增，格式：`{prefix}{yyyyMMdd}{4位序号}`（如 `ORD202606110001`）。

- 方法：`generate(String prefix)` 和 `generate(String prefix, LocalDate date)`
- 内部 Key：`{全局前缀}global:sequence:{prefix}:{date}`，按日期重置，每次调用以 `PEXPIREAT` 重置过期到当日结束
- 序号默认 4 位（`INITIAL_SEQUENCE_LENGTH=4`），超过 9999 后自动按位数扩展（不会抛异常）
- 时区：`Asia/Shanghai`

**失败语义：** Redis 返回 null 或发生任何异常时抛 `SequenceGenerationException`（携带 `prefix` 与 `date` 上下文）。该异常*
*继承 `ApiException`**，并携带 `ResultCode.INTERNAL_ERROR`（code=`50000`）错误码：引入 web 模块后由 `GlobalExceptionHandler`
兜底转为 `Result.failed(e.getErrorCode(), e.getMessage())`（code=50000）并映射 HTTP 500；未引入 web 模块则按普通 `RuntimeException` 上抛。

### RedisJsonSerializerFactory — 安全序列化

使用 `GenericJacksonJsonRedisSerializer`（Jackson 3，启用多态类型推断 `DefaultTyping.NON_FINAL`），`BasicPolymorphicTypeValidator` 收紧白名单：

- 默认放行 JDK 基础类型子类型：`String` / `Number` / `Boolean` / `Character` / `Collection` / `Map`
- 默认放行 `cn.nextdev.lightboot.` 包下的子类型
- 业务包通过 `light-boot.redis.serializer.base-packages` 追加
- 已移除顶层 `Object.class` 入口，杜绝任意类反序列化风险
- **禁止 JDK 包**：`java.util.Date`、枚举等 JDK 类型默认被拒，且 `base-packages` 拒绝 `java`/`javax`/`com.sun`/`sun`/`jdk`/`org.xml`/`org.w3c`
  等危险前缀（配置后启动即 fail-fast）——如需缓存此类类型，请使用业务 DTO 包裹；
  `java.time` 的类型均为 final（NON_FINAL 不写类型标识、不查询白名单），与白名单无关

> 业务自定义 DTO/Entity 必须将包路径加入白名单，否则反序列化时被 `BasicPolymorphicTypeValidator` 拒绝
> （报错信息含 "denied resolution" / "security"）。

### Spring Cache 集成

`RedisCachingAutoConfiguration` 启用 `@EnableCaching`，`RedisCacheAutoConfiguration` 配置 `RedisCacheManager`：

- 全局默认 TTL（`light-boot.cache.redis.time-to-live`）
- 按缓存名配置不同 TTL（`light-boot.cache.redis.caches.<name>.time-to-live`）
- 是否缓存 null 值（默认 false）
- Key 前缀管理
- 内置 `CacheErrorHandler` 并经 `CachingConfigurer` 接入缓存拦截链：缓存异常时记录 ERROR 日志但不抛出

---

## Bean 注册清单

| Bean                                          | 条件                                                                   |
|-----------------------------------------------|----------------------------------------------------------------------|
| `RedisTemplate<String, Object>`               | `@ConditionalOnMissingBean`，JSON 序列化                                 |
| `RedisService`（或 `FaultTolerantRedisService`） | `@ConditionalOnMissingBean`；`light-boot.redis.fault-tolerant=true` 时返回容错实现 |
| `SequenceService`                             | `@ConditionalOnMissingBean`                                          |
| `RedisCacheManager`                           | `@ConditionalOnMissingBean`                                          |
| `CacheErrorHandler` + `CachingConfigurer`     | `@ConditionalOnMissingBean`，错误处理器经 CachingConfigurer 接入缓存拦截链         |
| `RedisMetricsRecorder`                        | `@ConditionalOnMissingBean` + 类路径存在 `MeterRegistry`（需 actuator）      |

---

## 常量（RedisConstants）

| 常量                        | 值                    | 说明                       |
|---------------------------|----------------------|--------------------------|
| `SEQUENCE_KEY_PREFIX`     | `"global:sequence:"` | 序列号 Key 前缀               |
| `DATE_PATTERN`            | `"yyyyMMdd"`         | 日期格式                     |
| `INITIAL_SEQUENCE_LENGTH` | `4`                  | 序列号位数                    |
| `MAX_INITIAL_SEQUENCE`    | `9999L`              | 固定位数（4 位）序号的上限，超过后自动扩展位数 |

---

## 依赖

| 依赖                                  | 必选/可选 | 作用          |
|-------------------------------------|-------|-------------|
| lightboot-core                      | 必选    | `ApiException` / `ResultCode`（`SequenceGenerationException` 基类） |
| spring-boot-starter-data-redis      | 必选    | Redis 连接和操作 |
| commons-pool2                       | 必选    | Lettuce 连接池 |
| tools.jackson.core:jackson-databind（Jackson 3） | 必选    | JSON 序列化    |
| com.fasterxml.jackson.core:jackson-annotations | 必选    | 多态类型注解（`@class` 类型标识、可见性配置） |
| micrometer-core                     | 可选    | Redis 操作指标（需引入方提供 `MeterRegistry`） |
| spring-boot-configuration-processor | 可选    | IDE 配置提示    |
| lombok                              | 可选    | 简化代码        |

---

## 配置属性

### light-boot.redis（模块配置）

| 属性                         | 默认值     | 说明                                            |
|----------------------------|---------|-----------------------------------------------|
| `enabled`                  | `true`  | 模块总开关                                         |
| `key-prefix`               | `light-boot:` | 全局 Key 前缀                                     |
| `fault-tolerant`           | `false` | 是否启用读容错降级（开启后读异常返回空值，写仍抛）                     |
| `command-timeout-millis`   | `3000`  | Redis 命令超时（毫秒），覆盖 `spring.data.redis.timeout` |
| `connect-timeout-millis`   | `2000`  | Redis 连接超时（毫秒）                                |
| `pool.enabled`             | `true`  | 是否把本配置的有界池参数覆盖到 Boot 的带池客户端（不控制 Boot 是否建池，见注意事项；需 commons-pool2） |
| `pool.max-total`           | `16`    | 连接池上限                                         |
| `pool.max-idle`            | `8`     | 最大空闲连接                                        |
| `pool.min-idle`            | `2`     | 最小空闲连接                                        |
| `pool.max-wait-millis`     | `2000`  | 获取连接最大等待（毫秒）                                  |
| `serializer.base-packages` | `null`  | 反序列化类型白名单包路径（仅接受业务包，`java` 等危险前缀启动即被拒——`Date`/枚举等 JDK 类型请用业务 DTO 包裹；`java.time` 为 final 类，加白名单无效） |

### light-boot.cache.redis（缓存配置）

| 属性                           | 默认值     | 说明         |
|------------------------------|---------|------------|
| `key-prefix`                 | `app:`  | 缓存 Key 前缀  |
| `time-to-live`               | `3600`  | 默认 TTL（秒）  |
| `cache-null-values`          | `false` | 是否缓存 null  |
| `use-key-prefix`             | `true`  | 是否使用前缀（`false` 时完全禁用前缀：Key 仅由缓存 key 组成，不含 cacheName 与 `::` 分隔符，不同缓存的同名 key 会互相覆盖，共享 Redis 场景慎用） |
| `caches.<name>.time-to-live` | —       | 按缓存名配置 TTL |

---

## 扩展点

| 扩展点                | 方式                                                            |
|--------------------|---------------------------------------------------------------|
| 直接操作 RedisTemplate | `redisService.getRedisTemplate()`（通过 `@Getter` 暴露）            |
| 自定义 Key 定义         | 枚举实现 `RedisKeyDefinition`                                     |
| 自定义序列化白名单          | 配置 `light-boot.redis.serializer.base-packages`                      |
| 自定义缓存异常处理          | 注册 `CacheErrorHandler` Bean（自动让路且被框架的 `CachingConfigurer` 拾取接入）；完全接管可自定义 `CachingConfigurer`（框架配置退避） |

---

## 注意事项

- 分布式锁已独立为 `lightboot-redis-lock`（Redisson，可重入 + 看门狗）；本模块不再含锁
- `light-boot.redis.fault-tolerant=true` 开启读容错：读异常降级返回空值，写异常仍抛
- 序列号默认 4 位，超过 9999 后自动扩展位数（不会抛异常）；Redis 失败抛 `SequenceGenerationException`（继承 `ApiException`）
- 必须将业务实体包路径加入白名单，否则反序列化报错
- 序列化器使用 `GenericJacksonJsonRedisSerializer`（Jackson 3，带多态类型信息），RedisTemplate 与 CacheManager 共用同一 `RedisJsonSerializerFactory`
- 命令超时由 `light-boot.redis.command-timeout-millis` 控制（覆盖 `spring.data.redis.timeout`）；连接超时由 `connect-timeout-millis` 经 Lettuce `ClientOptions` / `SocketOptions` 生效，两者互不影响
- 连接池参数（`pool.*`）仅在 Boot 构建「带池」客户端配置时生效（需 `spring.data.redis.lettuce.pool.enabled=true` 或类路径存在 commons-pool2）
