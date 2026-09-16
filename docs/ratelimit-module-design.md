# ratelimit 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+

---

## 定位

可选的接口限流模块 `lightboot-ratelimit`，提供**基于 Redis 滑动窗口（ZSET + 单段 Lua）的注解式接口限流**。引入依赖并具备
Redis + AOP 后自动生效；触发限流抛 `RateLimitException`（继承 `ApiException`），由使用方工程的 web 模块
`GlobalExceptionHandler` 兜底为统一 `Result`（HTTP 429）。

> **背景**：限流从 web 模块内嵌能力升级为独立模块，配置前缀由 `light-boot.web.rate-limit` 改为 `light-boot.ratelimit`。

---

## 关键决策

1. **独立可选模块**：不污染 `lightboot-web`，不引入则无相关 Bean。
2. **注解驱动 + 多规则**：`@RateLimit` 可重复（`@RateLimits` 容器），同一方法可叠加多条规则，任一规则超限即拒绝。
3. **滑动窗口（ZSET + Lua）**：用 Redis 有序集合按请求时间戳记录成员，单段 Lua 脚本原子完成「清理过期成员 → 计数 → 判定 →
   写入 → 续期」，避免并发竞态。
4. **fail-open 故障降级**：Redis 异常时默认放行（`light-boot.ratelimit.fail-open=true`），限流降级不阻断业务。
5. **复用 ApiException 体系**：`RateLimitException` 继承 `ApiException`，错误码 `RateLimitResultCode.RATE_LIMIT = 42901`
   ，无需专用 handler。

### 非目标（YAGNI）

- 不提供令牌桶 / 漏桶等其它算法（滑动窗口已覆盖大多数接口限流场景）。
- 不内置限流指标的 actuator 端点（可由使用方按 micrometer 模式自行埋点）。

---

## 模块结构

```text
lightboot-ratelimit/
└── src/main/
    ├── java/cn/nextdev/lightboot/ratelimit/
    │   ├── RateLimit.java                  — 限流注解（可重复）
    │   ├── RateLimits.java                 — 多规则容器注解
    │   ├── RateLimitAspect.java            — AOP 切面（解析注解 + 调用判定）
    │   ├── RateLimitRedisService.java      — Redis 滑动窗口判定（ZSET + Lua）
    │   ├── RateLimitLongSerializer.java    — Lua 返回值 Long 序列化器（包私有）
    │   ├── config/
    │   │   ├── RateLimitAutoConfiguration.java — 自动配置（条件装配 service + aspect）
    │   │   └── RateLimitProperties.java        — 配置属性（light-boot.ratelimit.*）
    │   └── exception/
    │       ├── RateLimitException.java     — 限流异常（继承 ApiException，携带 Retry-After）
    │       └── RateLimitResultCode.java    — 错误码枚举（42901）
    └── resources/META-INF/
        ├── spring/...AutoConfiguration.imports
        └── additional-spring-configuration-metadata.json
```

- **包名** `cn.nextdev.lightboot.ratelimit`。
- **依赖**：`lightboot-core`（compile，`RateLimitException` / `RateLimitResultCode` 需要 `ApiException` / `IErrorCode`）+
  `spring-boot-starter-data-redis`（compile + optional，版本由 BOM 管理，`RateLimitRedisService` 编译期需要 `RedisTemplate`，不传递，使用方需自行引入）+
  `spring-boot-starter-aspectj`（optional，`RateLimitAspect` 需要 AspectJ，运行期需使用方提供）+
  `spring-boot-configuration-processor` / `lombok`（optional）。

---

## 自动配置与装配条件

`RateLimitAutoConfiguration`：

- 类级：`@ConditionalOnClass({RedisTemplate.class, RateLimitAspect.class})` +
  `@ConditionalOnProperty(light-boot.ratelimit.enabled, matchIfMissing=true)`。
- `RateLimitRedisService`：`@ConditionalOnMissingBean` + `@ConditionalOnBean(RedisTemplate.class)`（容器中需存在
  `RedisTemplate`）。
- `RateLimitAspect`：`@ConditionalOnMissingBean` + `@ConditionalOnBean(RateLimitRedisService.class)` +
  `@ConditionalOnClass(name="org.aspectj.lang.ProceedingJoinPoint")`（需 AOP 依赖）。

**任一条件不满足时静默跳过，不报错、不影响其他功能。** 因此运行期建议同时引入 `lightboot-web`（提供异常兜底）。

---

## 核心设计

### @RateLimit / @RateLimits — 注解

`@RateLimit` 为 `@Repeatable`，`@Target(METHOD)`。属性：

| 属性        | 类型       | 默认值   | 说明                                                                 |
|-----------|----------|-------|--------------------------------------------------------------------|
| `key`     | `String` | —（必填） | 限流 Key，字面量或 SpEL；无 `#` 视为字面量，含 `#` 解析为 SpEL；解析为空时回退为「声明类#方法名」按方法分桶 |
| `time`    | `int`    | `-1`  | 时间窗口（秒），`<=0` 使用 `light-boot.ratelimit.default-time`                     |
| `count`   | `int`    | `-1`  | 窗口内最大请求次数，`<=0` 使用 `light-boot.ratelimit.default-count`                  |
| `message` | `String` | `""`  | 自定义限流提示，空字符串使用 `light-boot.ratelimit.message`                            |

`@RateLimits` 为容器注解，属性 `value()` 为 `RateLimit[]`。切面会迭代方法上的所有规则，**任一规则超限即抛异常**。
规则提取时**容器优先**：方法上存在 `@RateLimits` 时只取其 `value()`，同方法上单独声明的 `@RateLimit` 被忽略。

### RateLimitAspect — 切面

`@Around("@annotation(RateLimit) || @annotation(RateLimits)")`：

1. 读取方法上的全部 `@RateLimit` 规则。
2. 逐条解析 `key`（SpEL 仅在含 `#` 时解析，失败抛 `IllegalStateException`；空值回退类名#方法名）、`time`/`count`（`<=0` 取默认）、
   `message`。
3. 调用 `RateLimitRedisService.allow(key, time, count)` 判定；任一规则返回 `false` → 抛
   `RateLimitException(message, time)`（携带窗口秒数，用于 `Retry-After`）。

### RateLimitRedisService — 滑动窗口判定

基于 Redis ZSET + 单段 Lua 脚本（`RATE_LIMIT_SCRIPT`，返回 `Long`）。KEY 为限流 Key（切面拼接 `rate:` 命名空间前缀，且
**不追加** `light-boot.redis.key-prefix` 全局前缀，实际 Key 为 `rate:{解析后的 key}`，redis-cli 排查时按 `rate:*` 查找），
每个请求以 `now + "-" + UUID` 为唯一成员、当前时间戳（ms）为 score 入队。

Lua 逻辑（原子）：

```text
ZREMRANGEBYSCORE key 0 windowStart   -- 清理窗口外的过期成员
current = ZCARD key                   -- 当前窗口内成员数
if current >= limit then              -- 达到上限
    PEXPIRE key (now - windowStart + 1000)
    return current + 1                -- 返回超限标志
end
ZADD key now member                   -- 记录本次请求
PEXPIRE key (now - windowStart + 1000)
return current + 1
```

`allow(key, time, count)`：执行脚本，返回 `current <= count`。脚本返回 `null` 或抛 `DataAccessException` 时按 `failOpen`
决定（默认放行）。序列化：参数用 `RedisSerializer.string()`，结果用 `RateLimitLongSerializer.INSTANCE`（纯文本
`Long.parseLong`）。

### RateLimitException / RateLimitResultCode — 异常与错误码

`RateLimitException extends ApiException`，覆盖 `getRetryAfterSeconds()` 返回窗口秒数（默认 `1L`），web 模块处理器据此写入
`Retry-After` 响应头。构造：`RateLimitException()` / `(String message)` / `(String message, long retryAfterSeconds)`（三个构造均携带
`RateLimitResultCode.RATE_LIMIT`；未显式传入窗口时重试等待默认 1 秒）。

`RateLimitResultCode implements IErrorCode`，枚举 `RATE_LIMIT(42901L, "请求过于频繁，请稍后重试")`。

> **异常映射：** 由使用方工程的 `GlobalExceptionHandler` 作为 `ApiException` 统一捕获，转为 `Result.failed(42901, message)`，经
`HttpStatusCodeResolver` 映射 HTTP 429，并写入 `Retry-After`。

---

## Bean 注册清单

| Bean                    | 条件                                                                           |
|-------------------------|------------------------------------------------------------------------------|
| `RateLimitRedisService` | `@ConditionalOnMissingBean` + 容器存在 `RedisTemplate` Bean                      |
| `RateLimitAspect`       | `@ConditionalOnMissingBean` + 存在 `RateLimitRedisService` Bean + 类路径有 AspectJ |

---

## 依赖

> 「必选」指**使用方需自备**（`spring-boot-starter-data-redis` 与 `spring-boot-starter-aspectj` 在模块 pom 中均为
> `<optional>true</optional>`，不随本模块传递）。

| 依赖                                    | 必选/可选   | 作用                                            |
|---------------------------------------|---------|-----------------------------------------------|
| `lightboot-core`                      | 必选      | `ApiException` / `IErrorCode`（`RateLimitException` / `RateLimitResultCode` 使用） |
| `spring-boot-starter-data-redis`      | 必选      | `RateLimitRedisService` 编译期需要 `RedisTemplate`（optional，版本由 BOM 管理） |
| `spring-boot-starter-aspectj`         | 必选（运行期） | `RateLimitAspect` 需要 AspectJ，否则切面不装配          |
| `spring-boot-configuration-processor` | 可选      | IDE 配置提示                                      |
| `lombok`                              | 可选      | 简化代码                                          |

---

## 配置属性

前缀 `light-boot.ratelimit`：

| 属性              | 类型        | 默认值            | 说明                                        |
|-----------------|-----------|----------------|-------------------------------------------|
| `enabled`       | `boolean` | `true`         | 模块总开关（仍需 Redis + AOP 到位）                  |
| `default-time`  | `int`     | `1`            | 默认时间窗口（秒），必须为正数，非正数启动即失败（fail-fast）      |
| `default-count` | `int`     | `100`          | 默认窗口内最大请求次数，必须为正数，非正数启动即失败（fail-fast）    |
| `fail-open`     | `boolean` | `true`         | Redis 故障时的放行策略；`true`=放行（限流降级），`false`=拒绝 |
| `message`       | `String`  | `请求过于频繁，请稍后重试` | 默认限流提示文案                                  |

---

## 注意事项

- **依赖前置**：限流模块编译期依赖 redis 模块与 aop；缺 `RedisTemplate` Bean（未引入/未启用 redis 模块）或缺 aop 依赖任一，
  切面不装配（静默跳过）。Redis 运行期不可用不影响装配，由 `fail-open` 决定放行/拒绝。异常的 HTTP 响应由使用方工程的
  web 模块兜底，建议同时引入 `lightboot-web`。
- **fail-open 语义**：默认 `true`，Redis 故障时放行——优先保业务可用；若限流是强约束（如开放 API 防刷），应设为 `false`
  让故障显式失败。
- **SpEL key**：仅在 `key` 含 `#` 时解析，引用方法入参（如 `#ip`、`#user.id`）；解析失败抛 `IllegalStateException`
  （fail loud：异常消息含表达式原文、原始 SpEL 异常为 cause，便于日志定位配置错误；不改变 HTTP 结果，与透传的 SpEL 异常一样映射 500）。
  另注：按参数名解析依赖 `-parameters` 编译（Spring 6.1+ 仅支持反射读参数名），缺失时参数引用解析为 `null`，会静默回退为
  「声明类#方法名」。
- **多规则短路**：多规则按顺序判定，**第一条超限规则即抛异常**，后续规则不再判定。
- **测试覆盖**：`RateLimitRedisServiceTest`（纯 Mockito，不依赖真实 Redis）覆盖 Lua 脚本返回 `null` 时按 `failOpen`
  放行/拒绝的容错分支；`RateLimitRedisServiceLuaIntegrationTest`（jedis-mock）覆盖 Lua 窗口边界与滑动语义；
  `RateLimitAutoConfigurationTest` 覆盖 default-time/default-count 启动校验（fail-fast）；`RateLimitAspectTest`
  覆盖切面拦截与 SpEL 解析（放行/超限/无注解放行、参数引用与属性导航、字面量短路、非法表达式 fail-loud、空 key 回退等）；
  `@ConditionalOnBean` 分层装配条件目前依赖编译验证 + 手测。
