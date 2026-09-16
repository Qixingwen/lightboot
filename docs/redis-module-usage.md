# redis — Redis 模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速验证](#快速验证)
- [功能详解](#功能详解)
    - [统一 Redis 操作](#统一-redis-操作)
    - [Key 定义管理](#key-定义管理)
    - [分布式锁](#分布式锁)
    - [序列号生成](#序列号生成)
    - [Spring Cache 集成](#spring-cache-集成)
- [配置属性一览](#配置属性一览)
- [自定义扩展](#自定义扩展)
- [常见问题](#常见问题)

---

## 模块简介

`lightboot-redis` 提供 Redis 集成的核心能力：

| 能力           | 说明                      | 默认状态 |
|--------------|-------------------------|------|
| 统一 Redis 操作  | 类型安全的 CRUD 操作封装         | 开启   |
| Key 定义管理     | 接口化 Key 前缀和超时管理         | 开启   |
| 读容错          | Redis 异常时读操作降级返回空值（写仍抛） | 关闭   |
| 序列号生成        | 按日期重置的原子递增 ID           | 开启   |
| Spring Cache | 声明式缓存注解支持               | 开启   |

> 分布式锁已独立为 `lightboot-redis-lock`（Redisson），见 [redis-lock 模块使用指南](./redis-lock-module-usage.md)。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-redis</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

引入后自动生效，无需 `@Import` 或 `@ComponentScan`。

---

## 快速验证

### 1. 配置 Redis 连接

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password

light-boot:
  redis:
    enabled: true
    key-prefix: "light-boot:"
    serializer:
      base-packages:
        - "com.example.entity"
```

### 2. 使用 RedisService

```java
@Service
@RequiredArgsConstructor
public class UserService {

    private final RedisService redisService;

    public void saveVerifyCode(String phone, String code) {
        // 存储 5 分钟有效期的验证码
        redisService.setString(UserRedisKey.SMS_CODE, phone, code);
    }

    public String getVerifyCode(String phone) {
        return redisService.getString(UserRedisKey.SMS_CODE, phone);
    }
}
```

### 3. 启动应用验证

```bash
# 控制台应无报错，Redis 连接成功
# 使用 redis-cli 验证：
redis-cli keys "light-boot:*"
```

---

## 功能详解

### 统一 Redis 操作

`RedisService` 封装了常用的 Redis 操作：

> - `listSize` / `setSize` / `zSetSize` 等长度方法在 Key 不存在时返回 `0`（不是 `null`）
> - `getObject(keyDef, key, Class)` 在缓存值类型不匹配时记录 WARN、删除脏 Key 并返回 `null`（自愈回源），不抛 `ClassCastException`
> - 指标埋点覆盖写操作与大 payload 读（`light-boot.redis.operations`，tag：`operation` / `result`）；`hasKey`、`setIsMember`、`zSetScore`、`zSetRank`、`setSize`、`listSize`、`zSetSize`、Hash `get` 等轻量点查询不埋点

#### String 操作

```java
// 存储字符串（带 Key 定义的超时时间）
redisService.setString(UserRedisKey.SMS_CODE, "13812348888", "123456");

// 存储字符串（自定义超时）
redisService.setString(UserRedisKey.SMS_CODE, "13812348888", "123456", 10, TimeUnit.MINUTES);

// 存储字符串（永不过期）
redisService.setStringWithoutExpire(UserRedisKey.CONFIG, "theme", "dark");

// 读取字符串（值为非 String 对象时返回其 toString()，需要 JSON 反序列化请用 getObject）
String code = redisService.getString(UserRedisKey.SMS_CODE, "13812348888");

// 存储对象（自动 JSON 序列化）
UserDTO user = new UserDTO(1L, "张三");
redisService.setObject(UserRedisKey.USER_INFO, "1", user);

// 读取对象（自动 JSON 反序列化）
UserDTO cached = redisService.getObject(UserRedisKey.USER_INFO, "1", UserDTO.class);

// SET NX（不存在时设置，常用于分布式锁）
Boolean set = redisService.setStringIfAbsent(UserRedisKey.TOKEN, "abc123", "1", 30, TimeUnit.MINUTES);
```

#### Hash 操作

```java
// 写入 Hash 字段
redisService.put(UserRedisKey.USER_DETAIL, "1", "name", "张三");
redisService.put(UserRedisKey.USER_DETAIL, "1", "age", 25);

// 批量写入 Hash
Map<String, String> fields = Map.of("name", "张三", "age", "25", "city", "北京");
redisService.putAll(UserRedisKey.USER_DETAIL, "1", fields);

// 读取 Hash 字段
Object name = redisService.get(UserRedisKey.USER_DETAIL, "1", "name");

// 获取 Hash 所有字段
Map<Object, Object> entries = redisService.getHashEntries(UserRedisKey.USER_DETAIL, "1");

// 删除 Hash 字段
redisService.hashDelete(UserRedisKey.USER_DETAIL, "1", "name", "age");

// 检查字段是否存在
boolean exists = redisService.hasKey(UserRedisKey.USER_DETAIL, "1", "name");
```

#### List 操作

```java
// 左入队 / 右入队
redisService.leftPush(TaskRedisKey.TASK_QUEUE, "default", task1);
redisService.rightPush(TaskRedisKey.TASK_QUEUE, "default", task2);

// 左出队 / 右出队
Object task = redisService.leftPop(TaskRedisKey.TASK_QUEUE, "default");

// 范围查询（获取索引 0-9 的元素）
List<Object> top10 = redisService.listRange(TaskRedisKey.TASK_QUEUE, "default", 0, 9);

// 列表长度
Long size = redisService.listSize(TaskRedisKey.TASK_QUEUE, "default");

// 裁剪列表（只保留前 100 条）
redisService.listTrim(TaskRedisKey.TASK_QUEUE, "default", 0, 99);
```

#### Set 操作

```java
// 添加元素
redisService.setAdd(TagRedisKey.ARTICLE_TAGS, "100", "Java", "Spring", "Redis");

// 移除元素
redisService.setRemove(TagRedisKey.ARTICLE_TAGS, "100", "Spring");

// 获取所有元素
Set<Object> tags = redisService.setMembers(TagRedisKey.ARTICLE_TAGS, "100");

// 判断元素是否存在
boolean isMember = redisService.setIsMember(TagRedisKey.ARTICLE_TAGS, "100", "Java");

// 集合大小
Long count = redisService.setSize(TagRedisKey.ARTICLE_TAGS, "100");
```

#### ZSet 操作

```java
// 添加带分数的元素
redisService.zSetAdd(RankRedisKey.USER_SCORE, "daily", "user1", 95.5);
redisService.zSetAdd(RankRedisKey.USER_SCORE, "daily", "user2", 88.0);

// 按排名范围查询（正序，分数低到高）
Set<Object> bottom10 = redisService.zSetRange(RankRedisKey.USER_SCORE, "daily", 0, 9);

// 按排名范围查询（倒序，分数高到低）
Set<Object> top10 = redisService.zSetReverseRange(RankRedisKey.USER_SCORE, "daily", 0, 9);

// 按分数范围查询
Set<Object> highScores = redisService.zSetRangeByScore(
    RankRedisKey.USER_SCORE, "daily", 90.0, 100.0);

// 获取元素分数
Double score = redisService.zSetScore(RankRedisKey.USER_SCORE, "daily", "user1");

// 获取元素排名
Long rank = redisService.zSetRank(RankRedisKey.USER_SCORE, "daily", "user1");

// 有序集合大小
Long count = redisService.zSetSize(RankRedisKey.USER_SCORE, "daily");

// 移除元素
redisService.zSetRemove(RankRedisKey.USER_SCORE, "daily", "user1");
```

#### 通用操作

```java
// 删除 Key
boolean deleted = redisService.delete(UserRedisKey.SMS_CODE, "13812348888");

// 批量删除
long deletedCount = redisService.delete(UserRedisKey.SMS_CODE,
    List.of("13812348888", "13900001111"));

// 使用 keyDef 的超时设置过期
redisService.expire(UserRedisKey.USER_INFO, "1");

// 自定义过期时间
redisService.expire(UserRedisKey.USER_INFO, "1", 30, TimeUnit.MINUTES);

// 设置过期时间点
redisService.expireAt(UserRedisKey.TEMP_DATA, "session123", Instant.now().plusSeconds(3600));

// 自增
long newValue = redisService.increment(CounterRedisKey.API_CALLS, "getUser", 1);

// 自增 1
long val = redisService.increment(CounterRedisKey.API_CALLS, "getUser");

// 自减 1
long val2 = redisService.decrement(CounterRedisKey.API_CALLS, "getUser");

// 检查 Key 是否存在
boolean exists = redisService.hasKey(UserRedisKey.USER_INFO, "1");

// 直接使用 RedisTemplate（特殊场景，通过 @Getter 暴露）
RedisTemplate<String, Object> template = redisService.getRedisTemplate();
template.execute(...);
```

### Key 定义管理

#### 定义业务 Key 枚举

```java
public enum UserRedisKey implements RedisKeyDefinition {

    /** 短信验证码，5 分钟过期 */
    SMS_CODE("auth:sms:", 5L),

    /** 用户信息缓存，30 分钟过期 */
    USER_INFO("user:info:", 30L),

    /** 用户详情 Hash，永不过期 */
    USER_DETAIL("user:detail:", null);

    private final String prefix;
    private final Long timeout; // 分钟

    UserRedisKey(String prefix, Long timeout) {
        this.prefix = prefix;
        this.timeout = timeout;
    }

    @Override
    public String getPrefix() { return prefix; }

    @Override
    public Long getTimeout() { return timeout; }
}
```

#### 使用方式

```java
// Key 自动构建：auth:sms:13812348888
String key = UserRedisKey.SMS_CODE.buildKey("13812348888");

// 配合 RedisService 使用
redisService.setString(UserRedisKey.SMS_CODE, "13812348888", "123456");
// 实际存储的 Key: light-boot:auth:sms:13812348888（加上全局前缀）
```

### 分布式锁

> 自 v1.0.0 起，分布式锁已独立为 **`lightboot-redis-lock`**（基于 Redisson，可重入 + 看门狗自动续期）。旧的简易锁
`RedisDistributedLock`（SET NX EX + Lua 解锁，返回 requestId）已移除，本模块不再含锁实现。
>
> 请引入 `lightboot-redis-lock`，注入 `RedissonDistributedLock`（包 `cn.nextdev.lightboot.redis.lock.redisson`），使用
`executeWithLock` / `executeWithTryLock`。详见 [redis-lock 模块使用指南](./redis-lock-module-usage.md)。

### 序列号生成

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final SequenceService sequenceService;

    public Order createOrder() {
        // 生成订单号：ORD202606110001
        String orderNo = sequenceService.generate("ORD");

        Order order = new Order();
        order.setOrderNo(orderNo);
        return orderRepository.save(order);
    }
}
```

**序列号格式说明：**

```text
ORD202606110001
│   │       │
│   │       └── 4 位序号（0001-9999），每日重置
│   └────────── 日期 yyyyMMdd
└────────────── 自定义前缀
```

**每日自动重置：** 序列号 Key 按日期命名，每次生成时以 `PEXPIREAT` 重置过期到当日结束。

> **时区：** 日期与「当日结束」均按 `Asia/Shanghai` 时区计算（代码硬编码）。跨时区部署时，日期切日点与 ID 中的日期
> 可能与本地时间不一致，需注意。

> **失败语义：** Redis 返回 null 或发生异常时，`generate(...)` 抛 `SequenceGenerationException`（携带 `prefix` 与 `date`
> ）。该异常继承 `ApiException` 并携带 `ResultCode.INTERNAL_ERROR`（code=`50000`），引入 web 模块后由 `GlobalExceptionHandler`
> 兜底，转为 `Result.failed(e.getErrorCode(), e.getMessage())`（code=50000）并映射 HTTP 500；未引入 web 模块则按普通 `RuntimeException` 上抛。

### Spring Cache 集成

#### 启用缓存

引入 redis 模块后自动启用 `@EnableCaching`，无需手动配置。

#### 使用缓存注解

```java
@Service
public class ProductService {

    // 缓存结果，Key: app:products::1
    @Cacheable(value = "products", key = "#id")
    public Product getById(Long id) {
        return productRepository.findById(id);
    }

    // 更新缓存
    @CachePut(value = "products", key = "#product.id")
    public Product update(Product product) {
        return productRepository.save(product);
    }

    // 删除缓存
    @CacheEvict(value = "products", key = "#id")
    public void delete(Long id) {
        productRepository.deleteById(id);
    }

    // 清除所有 products 缓存
    @CacheEvict(value = "products", allEntries = true)
    public void refreshAll() { ... }
}
```

#### 按缓存名配置不同 TTL

```yaml
light-boot:
  cache:
    redis:
      time-to-live: 3600         # 默认 1 小时
      caches:
        products:
          time-to-live: 7200     # 商品缓存 2 小时
        users:
          time-to-live: 1800     # 用户缓存 30 分钟
        config:
          time-to-live: 86400    # 配置缓存 24 小时
```

---

## 配置属性一览

所有配置使用 `light-boot.redis` 和 `light-boot.cache.redis` 前缀：

```yaml
light-boot:
  redis:
    enabled: true                      # 模块总开关
    key-prefix: "light-boot:"                # 全局 Key 前缀
    fault-tolerant: false              # 读容错降级（开启后读异常返回空值，写仍抛）
    command-timeout-millis: 3000       # 命令超时（毫秒）
    connect-timeout-millis: 2000       # 连接超时（毫秒）
    pool:                              # 有界池参数（仅在 Boot 构建带池客户端时覆盖生效，需 commons-pool2）
      enabled: true
      max-total: 16
      max-idle: 8
      min-idle: 2
      max-wait-millis: 2000
    serializer:
      base-packages:                   # 反序列化白名单
        - "com.example.entity"
        - "com.example.dto"
  cache:
    redis:
      key-prefix: "app:"               # 缓存 Key 前缀
      time-to-live: 3600               # 默认 TTL（秒）
      cache-null-values: false         # 是否缓存 null 值
      use-key-prefix: true             # 是否使用前缀（false 时完全禁用前缀：Key 仅由缓存 key 组成，不含 cacheName 与 :: 分隔符，不同缓存同名 key 会互相覆盖，共享 Redis 场景慎用）
      caches:                          # 按缓存名配置
        products:
          time-to-live: 7200
```

| 属性                                    | 类型             | 默认值     | 说明                        |
|---------------------------------------|----------------|---------|---------------------------|
| `light-boot.redis.enabled`                  | `boolean`      | `true`  | 模块总开关                     |
| `light-boot.redis.key-prefix`               | `String`       | `light-boot:` | 全局 Key 前缀                 |
| `light-boot.redis.fault-tolerant`           | `boolean`      | `false` | 是否启用读容错降级（开启后读异常返回空值，写仍抛） |
| `light-boot.redis.command-timeout-millis`   | `long`         | `3000`  | Redis 命令超时（毫秒）            |
| `light-boot.redis.connect-timeout-millis`   | `long`         | `2000`  | Redis 连接超时（毫秒）            |
| `light-boot.redis.pool.enabled`             | `boolean`      | `true`  | 是否把有界池参数覆盖到 Boot 的带池客户端（不控制 Boot 是否建池） |
| `light-boot.redis.pool.max-total`           | `int`          | `16`    | 连接池上限                     |
| `light-boot.redis.pool.max-idle`            | `int`          | `8`     | 最大空闲连接                    |
| `light-boot.redis.pool.min-idle`            | `int`          | `2`     | 最小空闲连接                    |
| `light-boot.redis.pool.max-wait-millis`     | `long`         | `2000`  | 获取连接最大等待（毫秒）              |
| `light-boot.redis.serializer.base-packages` | `List<String>` | `null`  | 反序列化类型白名单（仅接受业务包，`java` 等危险前缀启动即被拒；`java.time` 为 final 类，加白名单无效） |
| `light-boot.cache.redis.key-prefix`         | `String`       | `app:`  | 缓存 Key 前缀                 |
| `light-boot.cache.redis.time-to-live`       | `Integer`      | `3600`  | 默认缓存 TTL（秒）               |
| `light-boot.cache.redis.cache-null-values`  | `boolean`      | `false` | 是否缓存 null 值               |
| `light-boot.cache.redis.use-key-prefix`     | `boolean`      | `true`  | 是否使用 Key 前缀（`false` 时完全禁用前缀：Key 仅由缓存 key 组成，不含 cacheName 与 `::` 分隔符，不同缓存的同名 key 会互相覆盖，共享 Redis 场景慎用） |

---

## 自定义扩展

### 直接使用 RedisTemplate

`RedisService` 通过 `@Getter` 暴露了 `redisTemplate` 字段，需要执行 Pipeline、Lua 脚本等高级操作时直接获取：

```java
@RequiredArgsConstructor
@Service
public class AdvancedRedisService {

    private final RedisService redisService;

    public void executeLuaScript(String script, List<String> keys, Object... args) {
        RedisTemplate<String, Object> template = redisService.getRedisTemplate();
        template.execute(new DefaultRedisScript<>(script, Long.class), keys, args);
    }
}
```

### 自定义 CacheErrorHandler

框架内置的 `CacheErrorHandler` 已通过 `CachingConfigurer` 接入 Spring 缓存拦截链。注册自定义 `CacheErrorHandler`
Bean 时框架内置实例自动让路（`@ConditionalOnMissingBean`），且自定义实例会被框架的 `CachingConfigurer`
拾取接入拦截链；需要完全接管时可自定义 `CachingConfigurer`，届时框架配置自动退避。

```java
@Configuration
public class RedisConfig {

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /**
     * CacheErrorHandler 的 4 个方法均为抽象方法（接口无默认实现），自定义时需全部实现。
     */
    static class LoggingCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
            log.warn("缓存读取失败，降级为数据库查询: cache={}, key={}", cache.getName(), key, e);
        }

        @Override
        public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) { }

        @Override
        public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) { }

        @Override
        public void handleCacheClearError(RuntimeException e, Cache cache) { }
    }
}
```

---

## 常见问题

### 反序列化报错：类型不在白名单

反序列化被 `BasicPolymorphicTypeValidator` 拒绝（报错信息含 "denied resolution" / "security"）时，将业务实体类的包路径添加到白名单。注意白名单**禁止 JDK 包**：配置 `java`/`javax` 等危险前缀会在启动时直接失败（fail-fast），`java.util.Date`、枚举等 JDK 类型无法通过白名单放行——请改用业务 DTO 包裹此类值。`java.time` 的类型均为 final，序列化时不写类型标识、也不会查询白名单，**加入白名单无效**（读回为字符串）——此类值同样建议存入自定义 DTO 中序列化：

```yaml
light-boot:
  redis:
    serializer:
      base-packages:
        - "com.example.entity"
        - "com.example.dto"
```

### 如何使用分布式锁？

分布式锁已独立为 `lightboot-redis-lock`（基于 Redisson，可重入 + 看门狗自动续期）。请引入该模块并注入
`RedissonDistributedLock`，详见 [redis-lock 模块使用指南](./redis-lock-module-usage.md)。本模块（`lightboot-redis`
）不再提供锁实现。

### 序列号超过 9999 怎么办？

序列号默认 4 位（`0001`–`9999`），超过 9999 后**不会抛异常**，会自动按位数扩展（如 `10000`、`100000`）。因此无需特殊处理。

如希望始终固定位数或区分业务线，可：

1. 调整 `prefix` 区分不同业务线，降低单前缀的单日序号量
2. 联系框架维护者调整 `INITIAL_SEQUENCE_LENGTH`

### @Cacheable 不生效？

1. 确保方法所在的类是 Spring 管理的 Bean
2. 确保是外部调用（内部方法调用不走代理）
3. 确保返回值可序列化

### 如何关闭 Redis 模块？

```yaml
light-boot:
  redis:
    enabled: false
```

### 如何切换为 Lettuce 以外的客户端？

模块基于 `spring-boot-starter-data-redis`，默认使用 Lettuce。如需切换为 Jedis，在 pom.xml 中排除 Lettuce 并引入 Jedis
即可。注意：框架的超时与连接池定制仅作用于 Lettuce（定制器带 `@ConditionalOnClass(name = "io.lettuce.core.RedisClient")`），
切换为 Jedis 后 `light-boot.redis.command-timeout-millis` / `connect-timeout-millis` / `pool.*` 不再生效（静默跳过、不报错），
请改用 `spring.data.redis.timeout` 等 Boot 原生配置。
