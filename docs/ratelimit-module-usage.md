# ratelimit — 接口限流模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速验证](#快速验证)
- [功能详解](#功能详解)
    - [字面量 Key 限流](#字面量-key-限流)
    - [SpEL Key 限流](#spel-key-限流)
    - [多规则限流](#多规则限流)
- [注解属性](#注解属性)
- [配置属性一览](#配置属性一览)
- [异常与 HTTP 响应](#异常与-http-响应)
- [常见问题](#常见问题)

---

## 模块简介

`lightboot-ratelimit` 提供基于 Redis 滑动窗口（ZSET + Lua）的注解式接口限流：

| 能力    | 说明                                      | 默认状态               |
|-------|-----------------------------------------|--------------------|
| 注解式限流 | `@RateLimit` 标注在方法上即生效，支持字面量 / SpEL Key | 开启（需 Redis + AOP）  |
| 多规则叠加 | `@RateLimit` 可重复，`@RateLimits` 容器叠加多条规则 | 开启                 |
| 滑动窗口  | ZSET + 单段 Lua 原子判定，精确窗口内计数              | 开启                 |
| 故障降级  | Redis 异常时按 `fail-open` 放行或拒绝            | 放行（fail-open=true） |

> **设计原则：注解驱动，零侵入业务** — 限流逻辑由 AOP 切面处理，业务方法无需修改。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-ratelimit</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

> **生效前提**：限流是独立模块，需同时具备以下条件才装配：
>
> - `spring-boot-starter-data-redis`（提供 `RedisTemplate`，版本由 BOM 管理）+ Redis 连接
> - `spring-boot-starter-aspectj`（`RateLimitAspect` 需要 AspectJ）
> - `light-boot.ratelimit.enabled=true`（默认 true）
>
> 限流异常的 HTTP 响应由使用方工程的 `lightboot-web` 的 `GlobalExceptionHandler` 兜底，因此运行期建议同时引入 web
> 模块。缺 redis / aop 任一，限流静默跳过（不报错）。

---

## 快速验证

### 1. 配置 Redis 连接

参考 redis 模块的 `spring.data.redis` 配置，确保 `RedisTemplate` 可用。

### 2. 在接口上标注 @RateLimit

```java
import cn.nextdev.lightboot.ratelimit.RateLimit;

@RestController
@RequestMapping("/api/login")
public class LoginController {

    @RateLimit(key = "login", time = 60, count = 5)  // 60s 内同一 key 最多 5 次
    @PostMapping
    public Result<?> login(@RequestBody LoginDTO dto) {
        return userService.login(dto);
    }
}
```

### 3. 触发限流

连续请求超过阈值后，响应变为：

```json
{
    "code": 42901,
    "message": "请求过于频繁，请稍后重试",
    "data": null,
    "traceId": "0123456789abcdef0123456789abcdef"
}
```

> **说明：** traceId 由请求链路自动填充；非 web 场景或无 traceId 时该字段整体省略。

HTTP 状态码为 **429 Too Many Requests**，并携带 `Retry-After` 响应头（值为窗口秒数）。

---

## 功能详解

### 字面量 Key 限流

`key` 不含 `#` 时视为字面量，全局限流同一 Key：

```java
// 60s 内全局限流 "login" 5 次
@RateLimit(key = "login", time = 60, count = 5)
@PostMapping("/login")
public Result<?> login(@RequestBody LoginDTO dto) { ... }
```

实际 Redis Key 为 `rate:login`（切面统一加 `rate:` 前缀，且不带 `light-boot:` 全局前缀），redis-cli 排查时按 `rate:*` 查找。

### SpEL Key 限流

`key` 含 `#` 时按 SpEL 解析，引用方法入参，实现按对象/维度限流：

```java
// 按入参 ip 限流：同一 IP 1s 内最多 100 次
@RateLimit(key = "#ip", time = 1, count = 100)
public Result<?> api(String ip) { ... }

// 按用户限流：同一用户 60s 内最多 5 次
@RateLimit(key = "#user.id", time = 60, count = 5)
public Result<?> action(User user) { ... }
```

> SpEL 解析失败（表达式非法）会抛 `IllegalStateException`；解析结果为空时回退为「声明类#方法名」，按方法分桶限流。
>
> **前提：参数名解析依赖 `-parameters` 编译**（Spring 6.1+ 仅支持反射读取参数名）。使用方工程需为 `maven-compiler-plugin`
> 开启 `<parameters>true</parameters>`，否则 `#ip` 等参数引用解析为 `null`，会静默回退为「声明类#方法名」按方法限流（无任何告警）。

### 多规则限流

`@RateLimit` 可重复（`@RateLimits` 容器），叠加多条规则，**任一规则超限即拒绝**：

```java
@RateLimits({
    @RateLimit(key = "login", time = 60, count = 5),   // 全局 60s/5 次
    @RateLimit(key = "#ip", time = 1, count = 10)      // 同 IP 1s/10 次
})
@PostMapping("/login")
public Result<?> login(@RequestBody LoginDTO dto, String ip) { ... }
```

---

## 注解属性

| 属性        | 类型       | 默认值   | 说明                                                |
|-----------|----------|-------|---------------------------------------------------|
| `key`     | `String` | —（必填） | 限流 Key，字面量或 SpEL；无 `#` 视为字面量；为空时回退「类名#方法名」        |
| `time`    | `int`    | `-1`  | 时间窗口（秒），`<=0` 使用 `light-boot.ratelimit.default-time`    |
| `count`   | `int`    | `-1`  | 窗口内最大请求次数，`<=0` 使用 `light-boot.ratelimit.default-count` |
| `message` | `String` | `""`  | 自定义限流提示，空字符串使用 `light-boot.ratelimit.message`           |

---

## 配置属性一览

```yaml
light-boot:
  ratelimit:
    enabled: true                 # 是否启用限流（仍需 Redis + AOP 到位）
    default-time: 1               # 默认时间窗口（秒），必须为正数（非正数启动即失败）
    default-count: 100            # 默认窗口内最大请求次数，必须为正数（非正数启动即失败）
    fail-open: true               # Redis 故障时是否放行（true=放行降级，false=拒绝）
    message: "请求过于频繁，请稍后重试"
```

| 属性                             | 类型        | 默认值            | 说明                                 |
|--------------------------------|-----------|----------------|------------------------------------|
| `light-boot.ratelimit.enabled`       | `boolean` | `true`         | 是否启用限流（仍需 Redis + AOP 到位）          |
| `light-boot.ratelimit.default-time`  | `int`     | `1`            | 默认时间窗口（秒），必须为正数，非正数启动即失败（fail-fast） |
| `light-boot.ratelimit.default-count` | `int`     | `100`          | 默认窗口内最大请求次数，必须为正数，非正数启动即失败（fail-fast） |
| `light-boot.ratelimit.fail-open`     | `boolean` | `true`         | Redis 故障时放行策略；`true`=放行，`false`=拒绝 |
| `light-boot.ratelimit.message`       | `String`  | `请求过于频繁，请稍后重试` | 默认限流提示文案                           |

---

## 异常与 HTTP 响应

触发限流抛 `RateLimitException`（继承 `ApiException`，错误码 `RateLimitResultCode.RATE_LIMIT = 42901`），由 web 模块的
`GlobalExceptionHandler` 作为 `ApiException` 统一捕获：

- 响应体：`Result.failed(42901, "请求过于频繁，请稍后重试")`
- HTTP 状态码：**429 Too Many Requests**（经 `HttpStatusCodeResolver` 映射）
- 响应头：`Retry-After: <窗口秒数>`（由 `RateLimitException.getRetryAfterSeconds()` 提供）

> 未引入 web 模块时，`RateLimitException` 按 `ApiException`（`RuntimeException`）上抛，HTTP 响应由使用方自行处理。

---

## 常见问题

### 限流不生效？

按顺序排查：

1. 是否引入 `spring-boot-starter-data-redis` 并正确配置了 `spring.data.redis`（`RedisTemplate` Bean 存在）？
2. 是否引入 `spring-boot-starter-aspectj`（`RateLimitAspect` 需要 AspectJ）？
3. `light-boot.ratelimit.enabled` 是否被设为 `false`？
4. `@RateLimit` 是否标注在 Spring 管理的 Bean 的 **public** 方法上，且为外部调用（内部方法调用不走 AOP 代理）？
5. 是否使用了 JDK 接口代理（`spring.aop.proxy-target-class=false` 且 Bean 实现接口）？此时切面经 `MethodSignature.getMethod()`
   读取到的是接口方法，标注在实现类方法上的注解读不到 → 静默跳过限流。请保持默认的 CGLIB 代理（Boot 默认
   `proxy-target-class=true`），或把注解声明在接口方法上。

### Redis 故障时限流是放行还是拒绝？

由 `light-boot.ratelimit.fail-open` 决定（默认 `true`）。`true` 时 Redis 异常放行请求（限流降级，优先保业务可用）；`false`
时拒绝。若限流是强约束（开放 API 防刷），建议设为 `false` 让故障显式失败。

### 如何对同一接口的不同维度同时限流？

使用 `@RateLimits` 容器叠加多条 `@RateLimit`，例如「全局总量 + 单 IP」双规则。任一规则超限即拒绝。

### SpEL Key 解析失败会怎样？

`key` 含 `#` 时按 SpEL 解析；表达式非法会抛 `IllegalStateException`（启动后首次调用时）。解析结果为空（如参数为
null）则回退为「声明类#方法名」，按方法分桶。

### 如何关闭整个限流模块？

```yaml
light-boot:
  ratelimit:
    enabled: false
```
