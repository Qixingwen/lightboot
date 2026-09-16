# lightboot 使用说明

> 版本：1.0.0 | Java 21+ | Maven 3.9+ | Spring Boot 4.1.1 | 许可证：[MulanPSL-2.0](https://license.coscl.org.cn/MulanPSL2)

## 目录

- [项目简介](#项目简介)
- [快速开始](#快速开始)
- [模块总览](#模块总览)
- [core — 核心模块](#core--核心模块)
  - [统一响应 Result](#统一响应-result)
  - [错误码 IErrorCode / ResultCode](#错误码-ierrorcode--resultcode)
  - [业务异常 ApiException / Asserts](#业务异常-apiexception--asserts)
- [data — 数据模块](#data--数据模块)
  - [分页参数 PageParam](#分页参数-pageparam)
  - [分页结果 Page](#分页结果-page)
  - [带汇总的分页 PageSummary](#带汇总的分页-pagesummary)
  - [排序参数 SortParam](#排序参数-sortparam)
  - [分页排序参数 PageSortParam](#分页排序参数-pagesortparam)
- [web — Web 模块](#web--web模块)
  - [引入方式](#引入方式)
  - [全局异常处理](#全局异常处理)
  - [HTTP 状态码](#http-状态码)
  - [异步线程池 AsyncConfig](#异步线程池-asyncconfig)
  - [定时任务线程池 SchedulingConfig](#定时任务线程池-schedulingconfig)
  - [CORS 跨域配置 CorsConfig](#cors-跨域配置-corsconfig)
  - [Jackson 序列化配置 JacksonConfig](#jackson-序列化配置-jacksonconfig)
- [ratelimit — 接口限流模块](#ratelimit--接口限流模块)
- [logging — 日志模块](#logging--日志模块)
  - [引入方式](#引入方式-1)
  - [自动装配内容](#自动装配内容)
  - [快速验证](#快速验证)
  - [链路追踪 TraceContext / TraceFilter](#链路追踪-tracecontext--tracefilter)
  - [定时任务 TraceId 注入 ScheduledTraceAspect](#定时任务-traceid-注入-scheduledtraceaspect)
  - [请求日志 RequestLogFilter](#请求日志-requestlogfilter)
  - [日志脱敏 MaskingConverter / LogMasker](#日志脱敏-maskingconverter--logmasker)
  - [内置脱敏规则 MaskPattern](#内置脱敏规则-maskpattern)
  - [自定义脱敏策略](#自定义脱敏策略)
  - [配置 Logback](#配置-logback)
  - [异步线程 MDC 传播](#异步线程-mdc-传播)
  - [兼容性说明](#兼容性说明)
  - [常见问题](#常见问题)
  - [日志模块配置属性一览](#日志模块配置属性一览)
- [redis — Redis 模块](#redis--redis模块)
  - [引入方式](#引入方式-2)
  - [自动配置加载顺序](#自动配置加载顺序)
  - [Key 定义规范 RedisKeyDefinition](#key-定义规范-rediskeydefinition)
  - [统一操作服务 RedisService](#统一操作服务-redisservice)
  - [容错模式 FaultTolerantRedisService](#容错模式-faulttolerantredisservice)
  - [序列号生成 SequenceService](#序列号生成-sequenceservice)
  - [缓存配置](#缓存配置)
- [redis-lock — Redis 锁模块](#redis-lock--redis-锁模块)
- [可观测性与 Actuator 集成](#可观测性与-actuator-集成)
- [统一超时配置建议](#统一超时配置建议)
- [配置属性速查](#配置属性速查)
- [依赖版本](#依赖版本)

---

## 项目简介

lightboot 是一个基于 Spring Boot 4.x 的轻量级开发框架，提供以下能力：

- **统一 API 响应格式**：标准化的 `Result<T>` 包装与错误码体系
- **分页/排序抽象**：通用的分页参数、结果与汇总数据模型
- **Web 开箱即用**：全局异常处理（携带真实 HTTP 状态码）、CORS、Jackson 日期格式、异步/定时任务线程池
- **Redis 集成**：统一操作服务、序列号生成、Spring Cache 管理、读操作容错降级
- **分布式锁**：基于 Redisson 的可重入锁（看门狗自动续期）
- **接口限流**：基于 Redis 滑动窗口（ZSET + Lua）的注解式限流
- **日志增强**：链路追踪（TraceId）、HTTP 请求日志、日志脱敏、Micrometer 指标

所有模块基于 Spring Boot 自动配置，引入依赖即可生效；各模块相互独立，按需引入。

---

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.9+
- Spring Boot 4.1.x

### 安装到本地仓库

```bash
git clone https://github.com/Qixingwen/lightboot.git
cd lightboot
mvn clean install -DskipTests
```

### 在项目中引入

根据需要选择模块，在 `pom.xml` 中添加依赖：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>cn.nextdev</groupId>
            <artifactId>lightboot-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- 核心模块（web/redis/ratelimit/redis-lock 的基础；data 与 logging 不依赖 core） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-core</artifactId>
    </dependency>

    <!-- 数据模块（按需） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-data</artifactId>
    </dependency>

    <!-- Web 模块（按需） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-web</artifactId>
    </dependency>

    <!-- 日志模块（按需） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-logging</artifactId>
    </dependency>

    <!-- Redis 模块（按需，引入本模块即自动带入 spring-boot-starter-data-redis） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-redis</artifactId>
    </dependency>

    <!-- Redis 分布式锁模块（按需，基于 Redisson，引入本模块即自动带入 redisson 与 data-redis） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-redis-lock</artifactId>
    </dependency>

    <!-- 接口限流模块（按需，需 Redis + AOP 到位才生效） -->
    <dependency>
        <groupId>cn.nextdev</groupId>
        <artifactId>lightboot-ratelimit</artifactId>
    </dependency>
</dependencies>
```

> **依赖设计**：各模块的第三方依赖（AOP、actuator、sa-token、spring-tx、micrometer 等）均声明为
> `optional`，使用方按需自行引入。例外：`lightboot-redis` 的 data-redis 与 `lightboot-redis-lock` 的 redisson 为非 `optional`，
> 随模块传递引入（`lightboot-ratelimit` 的 data-redis 是 `optional`，需自行引入）。框架按 classpath 条件装配——缺什么就跳过对应能力，不会报错。

---

## 模块总览

| 模块 | ArtifactId | 说明 | 核心类 |
|------|-----------|------|--------|
| **core** | `lightboot-core` | 统一响应与异常体系 | `Result`, `ResultCode`, `IErrorCode`, `ApiException`, `Asserts` |
| **data** | `lightboot-data` | 分页与排序数据模型 | `PageParam`, `Page`, `PageSummary`, `Summary`, `SortParam`, `PageSortParam` |
| **web** | `lightboot-web` | Web 自动配置与全局异常处理 | `WebAutoConfiguration`, `GlobalExceptionHandler`, `SaTokenExceptionHandler`, `DatabaseExceptionHandler`, `HttpStatusCodeResolver`, `AsyncConfig`, `CorsConfig`, `JacksonConfig`, `SchedulingConfig`, `WebProperties` |
| **logging** | `lightboot-logging` | 链路追踪、请求日志、日志脱敏、HTTP 指标 | `TraceContext`, `TraceFilter`, `ScheduledTraceAspect`, `RequestLogFilter`, `MdcTaskDecorator`, `MaskingConverter`, `LogMasker`, `DefaultLogMasker`, `MaskPattern`, `HttpMetricsRecorder` |
| **redis** | `lightboot-redis` | Redis 操作封装、缓存、序列号、容错、指标 | `RedisTemplateAutoConfiguration`, `RedisCachingAutoConfiguration`, `RedisCacheAutoConfiguration`, `RedisServiceAutoConfiguration`, `RedisObservabilityAutoConfiguration`, `RedisJsonSerializerFactory`, `RedisService`, `FaultTolerantRedisService`, `SequenceService`, `RedisKeyDefinition` |
| **ratelimit** | `lightboot-ratelimit` | 基于 Redis 滑动窗口的注解式接口限流 | `RateLimit`, `RateLimits`, `RateLimitAspect`, `RateLimitRedisService`, `RateLimitProperties`, `RateLimitResultCode`, `RateLimitException` |
| **redis-lock** | `lightboot-redis-lock` | 基于 Redisson 的可重入分布式锁 | `RedissonAutoConfiguration`, `RedissonDistributedLock`, `RedissonProperties` |

---

## core — 核心模块

仅依赖 Lombok、slf4j-api 与 jackson-annotations（均非 Spring 依赖，无传递依赖）。提供统一响应格式、错误码体系和业务异常。

### 统一响应 Result

`Result<T>` 是框架中所有 API 的标准返回格式，包含四个字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | `long` | 状态码（0 = 成功，非 0 = 失败） |
| `message` | `String` | 提示信息 |
| `data` | `T` | 响应数据 |
| `traceId` | `String` | 请求追踪标识，构造时自动从 MDC 读取；无 traceId 时 JSON 序列化整体省略 |

该类为不可变类，所有字段在构造时设置，通过 Lombok `@Getter` 提供只读访问。

#### 静态工厂方法

**成功响应：**

```java
// 成功（无数据）
Result<Void> r1 = Result.success();

// 成功（带数据）
Result<User> r2 = Result.success(user);

// 成功（自定义消息 + 数据）
Result<User> r3 = Result.success(user, "查询成功");

// 成功（使用自定义 IErrorCode）
Result<Void> r4 = Result.success(OrderResultCode.SUCCESS);

// 成功（使用自定义 IErrorCode + 数据）
Result<User> r5 = Result.success(OrderResultCode.SUCCESS, user);
```

**失败响应：**

```java
// 失败（自定义消息，code = -1）
Result<Void> r6 = Result.failed("操作失败");

// 失败（使用错误码）
Result<Void> r7 = Result.failed(ResultCode.UNAUTHORIZED);

// 失败（使用错误码 + 自定义消息）
Result<Void> r8 = Result.failed(ResultCode.BAD_REQUEST, "手机号格式不正确");

// 失败（使用错误码 + 携带载荷数据）
Result<ErrorDetail> r9 = Result.failed(ResultCode.BAD_REQUEST, errorDetail);

// 业务码 40000（web 层映射为 HTTP 400）
Result<Void> r10 = Result.badRequest("参数校验失败");

// 业务码 40100（web 层映射为 HTTP 401）
Result<Void> r11 = Result.unauthorized();

// 业务码 40300（web 层映射为 HTTP 403）
Result<Void> r12 = Result.forbidden();
```

#### Controller 使用示例

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public Result<User> getUser(@PathVariable Long id) {
        User user = userService.getById(id);
        if (user == null) {
            return Result.failed("用户不存在");
        }
        return Result.success(user);
    }

    @PostMapping
    public Result<Void> createUser(@RequestBody @Valid UserDTO dto) {
        userService.create(dto);
        return Result.success();
    }
}
```

#### 判断成功/失败

```java
result.isSuccess();  // 严格判断 code == 0（即使通过 success(IErrorCode) 传入了非 0 码也返回 false）
result.isSuccess() == false;  // 即失败
```

### 错误码 IErrorCode / ResultCode

#### 内置错误码 ResultCode

| 枚举值 | code | message |
|--------|------|-----|
| `SUCCESS` | `0` | 操作成功 |
| `FAILED` | `-1` | 操作失败 |
| `AUTH_FAILED` | `10001` | 账号或密码错误 |
| `BAD_REQUEST` | `40000` | 请求参数错误或格式无效 |
| `UNAUTHORIZED` | `40100` | 登录会话失效，请重新登录 |
| `FORBIDDEN` | `40300` | 没有相关权限 |
| `NOT_FOUND` | `40400` | 请求的资源不存在 |
| `CONFLICT` | `40900` | 数据冲突，资源已存在 |
| `FILE_TOO_LARGE` | `41300` | 文件大小超过限制 |
| `INTERNAL_ERROR` | `50000` | 系统内部错误 |

#### 自定义错误码

实现 `IErrorCode` 接口即可定义业务专属错误码：

```java
public enum OrderErrorCode implements IErrorCode {

    ORDER_NOT_FOUND(10001, "订单不存在"),
    ORDER_STATUS_ERROR(10002, "订单状态异常"),
    ORDER_ALREADY_CANCELLED(10003, "订单已取消");

    private final long code;
    private final String message;

    OrderErrorCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public long getCode() { return code; }

    @Override
    public String getMessage() { return message; }
}
```

### 业务异常 ApiException / Asserts

#### ApiException

携带 `IErrorCode` 的运行时异常，全局异常处理器会自动捕获并转换为统一的 `Result` 响应。

```java
// 方式一：使用错误码
throw new ApiException(OrderErrorCode.ORDER_NOT_FOUND);

// 方式二：使用自定义消息
throw new ApiException("库存不足");

// 方式三：包装底层异常（保留异常链）
throw new ApiException(ioException);

// 方式四：自定义消息 + 底层异常
throw new ApiException("订单处理失败", rpcException);

// 方式五：错误码 + 自定义消息（覆盖 errorCode 的默认消息）
throw new ApiException(OrderErrorCode.ORDER_STATUS_ERROR, "订单处于不可操作状态");

// 方式六：错误码 + 自定义消息 + 底层异常（完整信息）
throw new ApiException(OrderErrorCode.ORDER_STATUS_ERROR, "订单状态异常", cause);
```

**构造方法一览：**

| 构造方法 | 说明 |
|---------|------|
| `ApiException(IErrorCode errorCode)` | 使用错误码，消息取自 `errorCode.getMessage()` |
| `ApiException(String message)` | 使用自定义消息，不携带错误码 |
| `ApiException(Throwable cause)` | 包装底层异常，保留异常链 |
| `ApiException(String message, Throwable cause)` | 自定义消息 + 底层异常 |
| `ApiException(IErrorCode errorCode, String message)` | 使用错误码 + 自定义消息（覆盖 `errorCode.getMessage()`） |
| `ApiException(IErrorCode errorCode, String message, Throwable cause)` | 错误码 + 自定义消息 + 底层异常 |

> **重试提示钩子：** 子类可覆写 `getRetryAfterSeconds()`（基类默认返回 `0`）。返回正数时，
> web 模块的 `GlobalExceptionHandler` 会将该秒数写入响应的 `Retry-After` 头。
> 限流模块的 `RateLimitException` 正是利用这一钩子携带限流窗口（默认 1 秒）。

#### Asserts 工具类

用于在业务校验中快速抛出 `ApiException`：

```java
// 无条件抛出
Asserts.fail(OrderErrorCode.ORDER_NOT_FOUND);
Asserts.fail("库存不足，需要 " + required + "，可用 " + available);

// 断言表达式为 true，否则抛出
Asserts.isTrue(count > 0, ResultCode.BAD_REQUEST);
Asserts.isTrue(count > 0, "数量必须大于 0");

// 断言对象不为 null，否则抛出
Asserts.notNull(user, OrderErrorCode.ORDER_NOT_FOUND);
Asserts.notNull(user, "用户不存在");

// 断言表达式为 false，否则抛出（仅 IErrorCode 重载）
Asserts.isFalse(hasConflict, OrderErrorCode.ORDER_ALREADY_CANCELLED);
```

> 注意：`isFalse` 仅有 `IErrorCode` 版本，无 `String` 重载。

---

## data — 数据模块

仅依赖 `jakarta.validation-api` 和 Lombok，提供通用的分页与排序数据模型。
所有校验注解**仅在控制器形参标注 `@Valid`/`@Validated` 时才生效**，框架不强制校验。

### 分页参数 PageParam

| 字段 | 类型 | 默认值 | 校验 | 说明 |
|------|------|--------|------|------|
| `current` | `long` | `1` | `@Min(1) @Max(1,000,000)` | 当前页码（从 1 开始） |
| `pageSize` | `long` | `20` | `@Min(1) @Max(100)` | 每页条数 |

**内置方法：**

- `getOffset()` — 计算数据库查询偏移量：`(current - 1) * pageSize`；
  溢出时夹紧到 `Long.MAX_VALUE`，`pageSize <= 0` 时返回 `0`，永不返回负数

```java
@GetMapping("/list")
public Result<Page<UserVO>> list(@Valid PageParam pageParam) {
    // pageParam.getCurrent()  → 当前页码
    // pageParam.getPageSize() → 每页条数
    // pageParam.getOffset()   → SQL 偏移量
    Page<UserVO> page = userService.listUsers(pageParam);
    return Result.success(page);
}
```

### 分页结果 Page

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `records` | `List<T>` | — | 当前页数据列表（构造时防御性拷贝为不可修改列表） |
| `total` | `long` | `0` | 总记录数 |
| `pageSize` | `long` | `0` | 每页条数 |
| `current` | `long` | `1` | 当前页码 |
| `totalPage` | `long` | `0` | 总页数（`pageSize > 0` 时自动计算：`(total + pageSize - 1) / pageSize`；否则为 0） |

> `current` 构造时如果传入值 `< 1`，将保留默认值 `1`。

**创建方式：**

```java
// 方式一：通过工厂方法 + PageParam（推荐，自动填充完整分页元数据并计算总页数）
List<User> users = mapper.selectPage(offset, pageSize);
long total = mapper.selectCount();
Page<User> page = Page.of(users, total, pageParam);

// 方式二：直接构造（完全控制分页参数）
Page<User> page = new Page<>(current, pageSize, total, users);

// 方式三：仅传入数据和总数
// 注意：分页元数据为字段默认值 current=1、pageSize=0、totalPage=0，
// 不会计算总页数，也并非 PageParam 的默认分页参数（1 页 / 20 条）。
// 下游需要 pageSize/totalPage 时请勿使用此方式。
Page<User> page = Page.of(users, total);
```

### 带汇总的分页 PageSummary

在分页数据基础上附加聚合汇总信息（如总金额、平均值等），一次请求返回明细与汇总。

**Summary 基类**包含一个 `total` 字段，表示全量记录总数，`PageSummary.of()` 会自动使用该值作为分页的 `total`。

```java
// 1. 定义汇总类（继承 Summary，total 字段已内置）
public class OrderSummary extends Summary {
    private BigDecimal totalAmount;
    private BigDecimal avgAmount;
    // getter/setter ...
}

// 2. 构造汇总数据（total 字段由 PageSummary.of() 自动用于分页）
OrderSummary summary = orderMapper.selectSummary(query);
summary.setTotal(totalCount);  // 设置全量记录总数

// 3. 使用 PageSummary（total 取自 summary.getTotal()）
List<OrderVO> orders = orderMapper.selectPage(offset, pageSize);
PageSummary<OrderVO, OrderSummary> result = PageSummary.of(orders, summary, pageParam);
```

**创建方式：**

```java
// 工厂方法（推荐，total 自动从 summary.getTotal() 获取）
PageSummary<OrderVO, OrderSummary> result = PageSummary.of(records, summary, pageParam);

// 直接构造
PageSummary<OrderVO, OrderSummary> result = new PageSummary<>(current, size, total, records, summary);
```

### 排序参数 SortParam

| 字段 | 类型 | 默认值 | 校验 | 说明 |
|------|------|--------|------|------|
| `sortField` | `String` | — | `@Pattern("[a-zA-Z_][a-zA-Z0-9_]{0,62}")` + `@Size(max=63)` | 排序字段名：字母或下划线开头，仅字母/数字/下划线，长度 1~63 |
| `sortType` | `String` | `"asc"` | `@Pattern("asc&#124;desc", 不区分大小写)` | 排序方向；允许显式传 `null` |

**安全边界（重要）：** `SortParam` 只做**格式**校验（阻断 SQL 片段注入），
**不**校验字段名是否为真实/敏感列名。拼装 `ORDER BY` 前，必须对 `sortField`
做**列名白名单**兜底校验，避免 `ORDER BY password` 这类按敏感列排序导致数据推断。

```java
@GetMapping("/list")
public Result<Page<UserVO>> list(@Valid PageSortParam param) {
    SortParam sort = param.getSortParam();          // 可能为 null
    if (sort != null) {
        String field = sort.getSortField();          // 排序字段
        String type  = sort.getSortType();           // "asc" 或 "desc"
    }
    // ...
}
```

### 分页排序参数 PageSortParam

组合 `PageParam` 和 `SortParam` 的请求参数，适用于同时需要分页和排序的查询接口。嵌套对象通过 `@Valid` 触发级联校验。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `pageParam` | `PageParam` | `new PageParam()` | 分页参数（自动初始化） |
| `sortParam` | `SortParam` | `null` | 排序参数（可选；是否应用及未设置时的默认排序行为由下游消费方自行决定） |

**内置方法：**

- `getOffset()` — 委托给内部 `PageParam.getOffset()`

---

## web — Web 模块

### 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-web</artifactId>
</dependency>
```

引入后自动装配以下配置（通过 Spring Boot 自动配置机制），无需手动添加 `@Import` 或 `@ComponentScan`。

**自动装配的组件：**

| 组件 | 类型 | 说明 |
|------|------|------|
| `JacksonConfig` | 配置 | JSON 日期时间格式化与时区设置 |
| `CorsConfig` | 配置 | CORS 跨域（同时支持 Spring MVC 和 Spring Security） |
| `AsyncConfig` | 配置 | 异步任务线程池（含 MDC 与请求上下文传播） |
| `SchedulingConfig` | 配置 | 定时任务线程池（替代默认单线程） |
| `WebProperties` | 属性 | `light-boot.web.http-status.enabled` 开关 |
| `SaTokenExceptionHandler` | 条件装配 | Sa-Token 认证/授权异常处理（需类路径存在 `sa-token-core`） |
| `DatabaseExceptionHandler` | 条件装配 | 数据库异常处理（需类路径存在 `spring-tx`） |
| `GlobalExceptionHandler` | 处理器 | 全局异常兜底处理（始终生效） |

### 全局异常处理

Web 模块提供三个异常处理器，按优先级顺序生效，自动将各类异常转换为统一的 `Result` 响应：

| 处理器 | 优先级 | 生效条件 | 处理范围 |
|--------|--------|---------|---------|
| `SaTokenExceptionHandler` | `HIGHEST_PRECEDENCE` | 类路径存在 `sa-token-core` | Sa-Token 认证/授权异常 |
| `DatabaseExceptionHandler` | `HIGHEST_PRECEDENCE` | 类路径存在 `spring-tx` | 数据完整性约束违反 |
| `GlobalExceptionHandler` | 最低优先级（兜底） | 始终生效 | API 异常、参数校验、HTTP 错误、IO、兜底 |

#### GlobalExceptionHandler — 异常处理明细

| 异常类型 | Result.code | 响应消息 | 日志级别 |
|---------|--------|---------|---------|
| `ApiException`（有 errorCode） | errorCode.code | errorCode.message（可被自定义消息覆盖）；`getRetryAfterSeconds() > 0` 时写 `Retry-After` 头 | WARN |
| `ApiException`（无 errorCode） | `-1` | e.getMessage() | WARN |
| `MethodArgumentNotValidException` / `BindException` | `40000` | 字段级校验错误详情 | WARN |
| `MethodArgumentTypeMismatchException` | `40000` | 参数类型错误提示 | WARN |
| `MissingRequestHeaderException` | `40000` | 缺少必需请求头提示 | WARN |
| `MissingServletRequestParameterException` | `40000` | 缺少必需参数提示 | WARN |
| `HttpMessageNotReadableException` | `40000` | 请求体格式错误或数据类型不匹配 | WARN |
| `HttpRequestMethodNotSupportedException` | `40000` | 不支持的请求方法提示 | WARN |
| `HttpMediaTypeNotSupportedException` | `40000` | 不支持的媒体类型提示 | WARN |
| `IllegalArgumentException` | `40000` | 请求参数不合法 | WARN |
| `NoHandlerFoundException` | `40400` | 请求的资源不存在 | WARN |
| `NoResourceFoundException` | `40400` | 请求的资源不存在 | WARN |
| `MaxUploadSizeExceededException` | `41300`（FILE_TOO_LARGE） | 文件大小超过限制 | WARN |
| `BadPaddingException` | `-1` | 数据解密异常 | ERROR |
| `IOException` | `-1` | 文件读写异常 | ERROR |
| `IllegalStateException` | `-1` | 系统状态异常 | ERROR |
| `NullPointerException` | `50000` | 系统内部错误 | ERROR |
| `RuntimeException` | `-1` | 运行时处理异常 | ERROR |
| `Exception`（兜底） | `-1` | 系统异常，请稍后重试 | ERROR |

#### SaTokenExceptionHandler — 异常处理明细

| 异常类型 | 响应 | HTTP |
|---------|------|------|
| `NotLoginException` | `Result.unauthorized()`（40100） | 401 |
| `NotPermissionException` / `NotRoleException` | `Result.forbidden()`（40300） | 403 |
| `SaTokenContextException` | `Result.failed("认证上下文异常，请检查系统配置")`（-1） | 500 |
| `SaTokenException`（兜底） | `Result.unauthorized()`（40100） | 401 |

#### DatabaseExceptionHandler — 异常处理明细

| 异常类型 | 响应 | HTTP |
|---------|------|------|
| `DuplicateKeyException` | `CONFLICT(40900)`「数据已存在，唯一约束冲突」 | 409 |
| `DataIntegrityViolationException` | `BAD_REQUEST(40000)`「数据完整性约束违反，操作失败」 | 400 |
| `DataAccessException` | `Result.failed("数据库操作异常")`（-1） | 500 |

> 数据库异常响应隐藏底层 SQL 信息，防止信息泄露。

### HTTP 状态码

框架默认让异常响应携带与 `Result.code` 语义对应的**真实 HTTP 状态码**，方便网关、监控、客户端按 HTTP 语义处理；
响应体仍是统一的 `Result`，业务结果由 `Result.code` 区分。

由 `HttpStatusCodeResolver` 通过一张**显式映射表**（而非前缀推断，避免歧义）解析 `Result.code` 到 HTTP 状态码：

| Result.code | HTTP 状态码 | 说明 |
|-------------|-------------|------|
| `0`（成功） | 200 OK | 正常响应 |
| `10001`（AUTH_FAILED） | 401 Unauthorized | 账号或密码错误 |
| `40000`（BAD_REQUEST） | 400 Bad Request | 参数校验/格式错误 |
| `40100`（UNAUTHORIZED） | 401 Unauthorized | 未登录 / 会话失效 |
| `40300`（FORBIDDEN） | 403 Forbidden | 无权限 |
| `40400`（NOT_FOUND） | 404 Not Found | 资源不存在 |
| `40900`（CONFLICT） | 409 Conflict | 数据冲突 / 唯一约束冲突 |
| `41300`（FILE_TOO_LARGE） | 413 Content Too Large | 文件大小超限 |
| `42901`（RATE_LIMIT） | 429 Too Many Requests | 接口限流（带 `Retry-After` 头） |
| `50000`（INTERNAL_ERROR） | 500 Internal Server Error | 系统内部错误 |
| `-1` / 其他 | 500 Internal Server Error | 兜底失败 |

**退回旧行为（全部 HTTP 200）：**

```yaml
light-boot:
  web:
    http-status:
      enabled: false   # 关闭后所有响应 HTTP 状态码退回 200，业务结果仅由 Result.code 区分
```

> 三个异常处理器已统一通过 `HttpStatusCodeResolver` 设置 HTTP 状态码，开关关闭时自动退回 200。

### 异步线程池 AsyncConfig

生产级异步任务执行器，支持 MDC 与 HTTP 请求上下文传播和优雅关闭。

**核心特性：**

- 自动传播 MDC（TraceId）与 `RequestContextHolder`，异步线程可获取链路标识与请求上下文
- Caller-Runs 拒绝策略（队列满时由调用线程执行并记录线程池状态 WARN 日志，防止任务丢失）
- 优雅关闭：等待运行中任务完成后再关闭
- 核心线程超时回收：节省低峰期资源
- 线程为非守护线程，JVM 停机时任务可正常完成
- `void` 返回值 `@Async` 方法的未捕获异常统一以 ERROR 级别记录（避免被静默吞掉）

**启用异步：** 框架已内置 `@EnableAsync`，无需手动添加。直接使用 `@Async` 注解即可。

```java
@Service
public class NotificationService {

    @Async("taskExecutor")
    public void sendAsyncEmail(String to, String subject) {
        // 异步执行，自动传播 MDC（TraceId）与 RequestContextHolder
        emailClient.send(to, subject);
    }
}
```

**配置参数（`application.yml`）：**

```yaml
light-boot:
  async:
    executor:
      core-pool-size: 10          # 核心线程数
      max-pool-size: 20           # 最大线程数
      queue-capacity: 500         # 队列容量
      keep-alive-seconds: 60      # 空闲线程存活时间
      thread-name-prefix: "async-executor-"
      allow-core-thread-timeout: true  # 核心线程超时回收
      await-termination-seconds: 60    # 优雅关闭等待时间
```

**配置属性一览：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `core-pool-size` | `int` | `10` | 核心线程数，CPU 密集型建议 = CPU 核心数，IO 密集型建议 = CPU 核心数 × 2 |
| `max-pool-size` | `int` | `20` | 最大线程数，须 >= core-pool-size |
| `queue-capacity` | `int` | `500` | 队列容量，小值(100-500)快速拒绝节省内存，大值(1000+)提高吞吐 |
| `keep-alive-seconds` | `int` | `60` | 空闲线程存活时间（秒） |
| `thread-name-prefix` | `String` | `async-executor-` | 线程名前缀 |
| `allow-core-thread-timeout` | `boolean` | `true` | 核心线程超时回收，低流量场景节省资源 |
| `await-termination-seconds` | `int` | `60` | 优雅关闭最大等待时间（秒） |

**调优建议：**

| 场景 | corePoolSize | maxPoolSize |
|------|-------------|-------------|
| CPU 密集型 | CPU 核心数 | CPU 核心数 |
| IO 密集型 | CPU 核心数 × 2 | CPU 核心数 × 4 |
| 低流量 | CPU 核心数 × 2 | CPU 核心数 × 2，开启 `allowCoreThreadTimeOut` |

### 定时任务线程池 SchedulingConfig

替代 Spring 默认的单线程定时任务调度器，支持多线程并行执行。

**核心特性：**

- 可配置线程池大小，支持多任务并行
- 自定义错误处理器，按类型分级记录（线程池拒绝 ERROR、任务中断 WARN、其他 ERROR 含异常栈），防止任务异常中断调度循环
- 优雅关闭：等待运行中任务完成后再关闭

框架已内置 `@EnableScheduling`，直接使用 `@Scheduled` 注解即可。

```java
@Component
public class DataCleanupTask {

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredData() {
        // 凌晨 2 点执行，线程池调度
    }
}
```

**配置参数：**

```yaml
light-boot:
  scheduling:
    thread-pool:
      pool-size: 10               # 线程池大小
      thread-name-prefix: "scheduled-task-"
      wait-for-tasks-to-complete-on-shutdown: true
      await-termination-seconds: 60
```

**配置属性一览：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `pool-size` | `int` | `10` | 线程池大小，轻量任务 2-5，中等 5-10，重度 10-20 |
| `thread-name-prefix` | `String` | `scheduled-task-` | 线程名前缀 |
| `wait-for-tasks-to-complete-on-shutdown` | `boolean` | `true` | 关闭时是否等待任务完成 |
| `await-termination-seconds` | `int` | `60` | 优雅关闭最大等待时间（秒），建议短任务 30-60s，长任务 120-300s |

### CORS 跨域配置 CorsConfig

同时支持 Spring MVC（`WebMvcConfigurer`）和 Spring Security（`CorsFilter`）的 CORS 配置。

> **默认关闭，需显式开启：** `light-boot.cors.enabled` 默认为 `false`。未显式设置为 `true` 时，
> `corsConfigurer`（MVC）与 `corsFilter`（过滤器，Spring Security 开启 `cors()` 时按此名称复用）
> 两个 Bean 均不创建，由业务方自行配置 CORS。
>
> **注意：** 两个 Bean 同受 `enabled` 控制，同时生效时同一请求会分别经过过滤器链与 MVC 映射两条
> CORS 处理路径——集成 Security 的应用建议按链路只保留其一。

```yaml
light-boot:
  cors:
    enabled: true                 # 必须显式设为 true 才装配 CORS（默认 false）
    allowed-origins:
      - "https://yourdomain.com"
      - "http://localhost:3000"
    allowed-methods:
      - "GET"
      - "POST"
      - "PUT"
      - "DELETE"
      - "OPTIONS"
    allowed-headers:
      - "*"
    exposed-headers:
      - "Content-Disposition"
      - "Authorization"
    allow-credentials: false
    max-age: 3600
    path-pattern: "/**"
```

**配置属性一览：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `Boolean` | `false` | 是否启用 CORS 自动装配；**默认关闭**，需显式 `true` |
| `allowed-origins` | `List<String>` | `[]`（空） | 允许的源；内部以 `allowedOriginPatterns` 注册，支持 `*` 通配 |
| `allowed-methods` | `List<String>` | `["GET","POST","PUT","DELETE","OPTIONS"]` | 允许的 HTTP 方法 |
| `allowed-headers` | `List<String>` | `["*"]` | 允许的请求头 |
| `exposed-headers` | `List<String>` | `["Content-Disposition","Authorization"]` | 暴露的响应头 |
| `allow-credentials` | `Boolean` | `false` | 是否允许携带凭证 |
| `max-age` | `Long` | `3600` | 预检请求缓存时间（秒） |
| `path-pattern` | `String` | `"/**"` | CORS 生效的 URL 路径模式 |

> **Fail-fast 校验：** 启动期 `CorsConfig.validate()` 会校验：当 `allow-credentials: true` 时，
> `allowed-origins` 不可使用 `"*"`，必须指定具体域名。违反时抛 `IllegalStateException` 阻止应用启动
> （这是框架强制的安全约束——通配源无法安全携带凭证；并非 CORS 规范禁止）。
>
> 所有 setter 均为 null-safe，绑定配置时传 null 不会导致异常。

### Jackson 序列化配置 JacksonConfig

基于 Jackson 3（`tools.jackson`）自动配置 JSON 日期时间格式和时区，**无需额外配置**：

| 类型 | 格式 |
|------|------|
| `LocalDateTime` | `yyyy-MM-dd HH:mm:ss` |
| `LocalDate` | `yyyy-MM-dd` |
| `LocalTime` | `HH:mm:ss` |
| `Date` 等 | Jackson 3 默认 ISO-8601 文本（本模块未定制） |
| 时区 | `Asia/Shanghai` |

额外行为：

- 忽略未知 JSON 属性（Jackson 3 中 `FAIL_ON_UNKNOWN_PROPERTIES` 默认已关闭）
- java time 支持内置于 Jackson 3 databind，无需注册 jsr310 等额外模块

---

## ratelimit — 接口限流模块

基于 Redis 滑动窗口（ZSET + 单段 Lua 脚本原子执行）的注解式接口限流。
触发限流抛 `RateLimitException`（继承 `ApiException`，错误码 `42901`），由 web 模块的
`GlobalExceptionHandler` 统一捕获，转为 `Result.failed(42901, "请求过于频繁，请稍后重试")`（HTTP 429），
并经 `Retry-After` 头携带限流窗口（默认 1 秒）。Redis 故障时按 `light-boot.ratelimit.fail-open`
（默认 `true`）放行，不阻断业务。

> **引入方式：** 限流是独立模块。编译期依赖 `spring-boot-starter-data-redis`（optional，版本由 BOM 管理，
> 需要 `RedisTemplate`）与 AOP（`spring-boot-starter-aspectj`，optional）。运行期需使用方提供：Redis（容器中有 `RedisTemplate` Bean）
> 与 AOP 依赖，建议同时引入 `lightboot-web`（限流异常的 HTTP 响应由其 `GlobalExceptionHandler` 兜底）。
> 缺任一条件时静默降级为不限流，不报错。

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-ratelimit</artifactId>
</dependency>
```

**用法示例：**

```java
import cn.nextdev.lightboot.ratelimit.RateLimit;

// 字面量 key：60s 内同一 key 最多 5 次
@RateLimit(key = "login", time = 60, count = 5)
@PostMapping("/login")
public Result<?> login(@RequestBody LoginDTO dto) { ... }

// SpEL key：按参数限流（#ip 引用方法入参），1s 内最多 100 次
@RateLimit(key = "#ip", time = 1, count = 100)
public Result<?> api(String ip) { ... }

// 多规则：可重复标注，任一规则超限即拒绝
@RateLimit(key = "login", time = 60, count = 5)
@RateLimit(key = "#ip", time = 1, count = 10)
@PostMapping("/login")
public Result<?> login(@RequestBody LoginDTO dto, String ip) { ... }

// 也可使用 @RateLimits 容器注解（容器存在时优先，此时同方法上单独声明的 @RateLimit 被忽略）
@RateLimits({
    @RateLimit(key = "login", time = 60, count = 5),
    @RateLimit(key = "#ip", time = 1, count = 10)
})
```

**注解属性：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `key` | `String` | —（必填） | 限流 Key，支持字面量（如 `"login"`）或 SpEL（如 `#ip`、`#user.id`）；解析为空时回退为「类名#方法名」按方法分桶 |
| `time` | `int` | `-1` | 时间窗口（秒），**非正数**（含 -1）使用 `light-boot.ratelimit.default-time` |
| `count` | `int` | `-1` | 窗口内最大请求次数，**非正数**（含 -1）使用 `light-boot.ratelimit.default-count` |
| `message` | `String` | `""` | 自定义限流提示，空字符串表示使用 `light-boot.ratelimit.message` |

**配置属性一览（`light-boot.ratelimit.*`）：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `light-boot.ratelimit.enabled` | `boolean` | `true` | 是否启用限流（仍需 Redis + AOP 到位才生效） |
| `light-boot.ratelimit.default-time` | `int` | `1` | 默认时间窗口（秒），必须为正数（作为 `RateLimitException` 的 `Retry-After` 秒数传递），非正数启动即失败 |
| `light-boot.ratelimit.default-count` | `int` | `100` | 默认窗口内最大请求次数，必须为正数，非正数启动即失败 |
| `light-boot.ratelimit.fail-open` | `boolean` | `true` | Redis 故障时的放行策略；`true`=放行（限流降级，不阻断业务），`false`=拒绝 |
| `light-boot.ratelimit.message` | `String` | `请求过于频繁，请稍后重试` | 默认限流提示文案 |

**条件装配（按 Bean 分层）：**

- 类级：`light-boot.ratelimit.enabled=true`（默认）+ 类路径存在 `RedisTemplate`
- `RateLimitRedisService` Bean：另需容器中存在 `RedisTemplate` Bean
- `RateLimitAspect` Bean：另需 `RateLimitRedisService` Bean + 类路径存在 AspectJ
  （缺 AOP 时仅装配 service 而无切面，注解不生效，不报错）

---

## logging — 日志模块

提供链路追踪（TraceId）、HTTP 请求日志、日志脱敏与 HTTP 指标四大能力，引入依赖即自动生效。

| 能力 | 说明 | 默认状态 |
|------|------|---------|
| TraceId 全链路追踪 | 每个请求自动分配唯一 TraceId，贯穿日志和响应头 | 开启 |
| 日志脱敏 | 自动脱敏日志中的手机号、身份证号、银行卡号、邮箱、凭证 | 开启 |
| HTTP 请求日志 | 记录每个请求的方法、URI、客户端 IP、状态码和耗时 | 开启 |
| HTTP 指标 | 引入 actuator 后上报 `light-boot.http.requests` | 条件装配 |

**设计原则：零侵入** — 业务代码无需任何修改，所有功能通过配置开关控制。

### 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-logging</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

### 自动装配内容

| 组件 | 类型 | 条件 | 说明 |
|------|------|------|------|
| `loggingContextHolderInitializer` | 初始化 | 始终生效 | 注入 Spring 上下文到 `LoggingContextHolder`，供 Logback Converter 桥接 |
| `traceFilter` | 过滤器（`@Order` 最高） | 需要 Servlet API | 为 HTTP 请求注入 TraceId |
| `requestLogFilter` | 过滤器（`@Order` +10） | 需要 Servlet API | 记录 HTTP 请求日志 |
| `logMasker` | Bean | 无自定义 `LogMasker` Bean 时生效 | 默认脱敏实现 |
| `maskingStartupLogger` | 监听器 | 脱敏开启时 | 启动完成时提醒脱敏接线（可覆盖消音） |
| `scheduledTraceAspect` | 切面 | 需要 AOP 依赖 | 为定时任务注入 TraceId |
| `mdcTaskDecorator` | TaskDecorator | 始终生效 | MDC 传播装饰器，供 `@Async` 线程池/业务复用 |
| `httpMetricsRecorder` / `httpMetricsFilter` | 指标 | 需要 actuator + Servlet API | HTTP 请求耗时指标 |

**条件装配：**

- 整个模块可通过 `light-boot.logging.enabled=false` 关闭（含 HTTP 指标采集）
- TraceId 过滤器可通过 `light-boot.logging.trace.enabled=false` 单独关闭
- 请求日志可通过 `light-boot.logging.request.enabled=false` 单独关闭
- 日志脱敏可通过 `light-boot.logging.mask.enabled=false` 单独关闭

### 快速验证

#### 1. 配置 Logback（必须）

在 `src/main/resources/logback-spring.xml` 中注册脱敏 Converter 并启用 TraceId 输出：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 注册脱敏 Converter -->
    <conversionRule conversionWord="maskMsg" class="cn.nextdev.lightboot.logging.mask.MaskingConverter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- %X{traceId:-} 输出 TraceId，%maskMsg 替代 %msg 实现脱敏 -->
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{40} - %maskMsg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
```

#### 2. 启动应用，发送请求

```bash
curl http://localhost:8080/api/user/login -H "Content-Type: application/json" -d '{"phone":"13812348888"}'
```

#### 3. 查看日志输出

```text
2026-05-12 14:30:00.123 [http-nio-8080-exec-1] [a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6] INFO  c.e.demo.UserController - 用户手机号 138****8888 登录成功
2026-05-12 14:30:00.156 [http-nio-8080-exec-1] [a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6] INFO  c.n.l.l.r.RequestLogFilter - HTTP POST /api/user/login from 192.168.1.100 -> 200 (33ms)
```

- `[a1b2c3d4...]` — 自动生成的 TraceId（32 位纯小写 hex）
- `138****8888` — 手机号自动脱敏
- `from 192.168.1.100` — 客户端 IP（默认取 TCP 直连地址；配置受信代理后才解析转发头）
- `-> 200 (33ms)` — 响应状态和耗时

#### 4. 检查响应头

```bash
curl -I http://localhost:8080/api/user/login
# 响应头包含：X-Trace-Id: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
```

### 链路追踪 TraceContext / TraceFilter

为每个 HTTP 请求自动分配全局唯一的 TraceId，贯穿日志、响应头和下游调用，实现请求级别的日志关联。

#### TraceContext

基于 `ThreadLocal` + SLF4J MDC 的上下文持有器，核心 API：

| 方法 | 说明 |
|------|------|
| `TraceContext.set(traceId)` | 设置当前线程的 TraceId（同时写入 MDC） |
| `TraceContext.get()` | 获取当前线程的 TraceId |
| `TraceContext.clear()` | 清理当前线程的 TraceId（防止线程池复用泄漏） |
| `TraceContext.generate()` | 生成 32 位纯小写十六进制 TraceId |

**MDC Key：** `traceId`，在 Logback 日志格式中通过 `%X{traceId}` 输出。

#### TraceFilter

Web 请求 TraceId 过滤器，处理流程：

1. 从请求头读取上游 TraceId（支持网关/微服务透传）
2. 校验格式（16-64 位小写十六进制），不合法则重新生成
3. 写入 `TraceContext`（程序内获取）和响应头（前端/下游获取）
4. 请求结束后清理，防止线程池复用时泄漏

**格式校验规则：** 仅接受 16-64 位**小写**十六进制字符（`[a-f0-9]`）；含大写字母会被判为非法并重新生成
（链路 ID 会断裂），自建 TraceId 时请注意使用小写。

#### 微服务透传

上游服务在响应头中返回 `X-Trace-Id`，下游服务自动读取并延续同一个 TraceId，形成完整调用链路。

```text
客户端 → 网关 (生成 TraceId) → 服务A (继承 TraceId) → 服务B (继承 TraceId)
```

#### 业务代码中获取 TraceId

```java
import cn.nextdev.lightboot.logging.trace.TraceContext;

// 获取当前请求的 TraceId（可用于下游 HTTP 调用透传）
String traceId = TraceContext.get();

// 通过 RestTemplate 透传给下游服务
HttpHeaders headers = new HttpHeaders();
headers.set("X-Trace-Id", traceId);
```

### 定时任务 TraceId 注入 ScheduledTraceAspect

为 `@Scheduled` 定时任务自动注入 **纯 hex 格式的 TraceId**（与 HTTP 请求一致，满足 `^[a-f0-9]{16,64}$`），
确保定时任务日志可通过 TraceId 关联；同时以独立 MDC key `traceSource=scheduled` 标记来源。

> **为何不用 `sched-` 前缀：** TraceId 始终保持纯 hex 单一契约，定时任务发起下游 HTTP 调用透传
> `X-Trace-Id` 时，下游 `TraceFilter` 能正确接受，链路在「定时任务 → HTTP」边界不会断裂。
> 来源区分交给独立的 `traceSource` MDC key，不污染 TraceId 本身。

```java
@Component
public class DataCleanupTask {

    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanExpiredData() {
        // TraceId 已自动注入（32 位纯 hex，与 HTTP 请求一致）
        // MDC 中 traceId 可用，日志自动关联；traceSource=scheduled 标记来源
        log.info("开始清理过期数据");
    }
}
```

**在日志中输出来源（可选）：** 在 pattern 中追加 `%X{traceSource:-}`，定时任务日志将输出
`[<32位hex>] [scheduled]`，HTTP 请求日志输出 `[<32位hex>] []`。不输出 `traceSource` 不影响任何功能。

> 此切面需要 AOP 依赖（Spring Boot 4 中为 `spring-boot-starter-aspectj`），未引入时自动跳过。

### 请求日志 RequestLogFilter

自动记录每个 HTTP 请求的方法、URI、查询参数、客户端 IP、响应状态和耗时。

**日志输出格式：**

```text
HTTP GET /api/users?page=1&size=10 from 192.168.1.100 -> 200 (35ms)
```

**特性：**

- 不记录请求体/响应体内容，避免大文件场景下的内存和性能问题
- 查询参数处理顺序：CRLF/制表符清洗 → 脱敏 → 截断（超过 200 字符截断并标记 `...(truncated)`）
- 代理环境 IP 伪造防护：仅当 TCP 直连地址命中 `trusted-proxies` CIDR 列表时，才采信
  `X-Forwarded-For` / `X-Real-IP` 等转发头；默认空 = 不信任任何代理，直接使用 TCP 连接地址
- 支持路径排除（Ant 风格匹配），默认排除 `/actuator/**` 和 `/favicon.ico`

**慢请求标记：**

通过 `light-boot.logging.request.slow-threshold-ms`（默认 `1000` 毫秒）配置慢请求阈值。
请求耗时**达到或超过**该阈值时，日志级别从 INFO 提升为 **WARN**，并在末尾追加 `[SLOW]` 标记：

```text
WARN  HTTP GET /api/users/export from 192.168.1.100 -> 200 (2350ms) [SLOW]
```

设为 `0` 或负值可禁用慢请求标记（所有请求一律 INFO）。

### 日志脱敏 MaskingConverter / LogMasker

基于 Logback 自定义 Converter 的日志脱敏机制，在日志输出时自动对敏感信息进行掩码处理。

#### 配置步骤

**第一步：** 在 `logback-spring.xml` 中注册 `conversionRule`：

```xml
<configuration>
    <!-- 注册脱敏转换器 -->
    <conversionRule conversionWord="maskMsg" class="cn.nextdev.lightboot.logging.mask.MaskingConverter"/>
    <conversionRule conversionWord="maskEx" class="cn.nextdev.lightboot.logging.mask.MaskingThrowableConverter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <!-- 使用 %maskMsg 替代 %msg 或 %m -->
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{36} - %maskMsg%n</pattern>
        </encoder>
    </appender>
</configuration>
```

**第二步：** 无需其他配置，引入依赖后自动生效。

**初始化策略：** `MaskingConverter` 优先从 Spring 容器获取 `LogMasker` Bean（支持自定义实现），
容器未就绪时降级为 `DefaultLogMasker`。`light-boot.logging.mask.enabled=false` 时输出一次 WARN 并变为
no-op（原样输出）。该开关在 Spring 容器注入后生效，容器就绪前的极早期日志仍按默认规则脱敏。

> **默认不脱敏应用日志：** 仅 HTTP 请求日志的查询参数由 `RequestLogFilter` 内部调用 `LogMasker` 自动脱敏；
> 应用日志消息和异常栈需要你在 `logback-spring.xml` 中注册并使用 `%maskMsg`（消息）/ `%maskEx`（异常栈）后才脱敏。
> 应用就绪时若未配置，`MaskingStartupLogger` 会输出一次 WARN 提示。
>
> **完成接线后消音：** 确认已注册 `conversionRule` 并在 pattern 中使用了 `%maskMsg`/`%maskEx` 后，
> 置 `light-boot.logging.mask.wired=true` 即可抑制该启动 WARN（框架不检测运行时 layout，由使用方自行声明；
> 也可注册同名 Bean 覆盖 `MaskingStartupLogger` 消音）。
>
> 如果不需要脱敏，可使用 `%msg` 替代 `%maskMsg`，`%ex` 替代 `%maskEx`，其余配置不变。

### 内置脱敏规则 MaskPattern

`DefaultLogMasker` 内置以下脱敏规则，按声明顺序（枚举序）依次应用：

| 规则 | 匹配内容 | 脱敏效果 | 示例 |
|------|---------|---------|------|
| `PHONE` | 手机号（1[3-9] 开头，11 位） | 中间 4 位替换为 `****` | `138****8888` |
| `ID_CARD` | 身份证号（18 位） | 中间 11 位替换为 `***********` | `110***********1234` |
| `BANK_CARD` | 银行卡号（4/5/6 开头） | 中间 8 位替换为 `********` | `6222********1234` |
| `EMAIL` | 邮箱地址 | `@` 前保留首字符，其余替换为 `***` | `t***@example.com` |
| `CREDENTIAL_BEARER` | 凭证键后的 `bearer/basic/jwt <值>` | 整体替换为 `******`（不保留 scheme 词） | `Authorization: ******` |
| `CREDENTIAL_JSON` | JSON 中的凭证键值对（`"password":"xxx"`） | 值替换为 `"******"`（保留引号） | `"password":"******"` |
| `CREDENTIAL` | 凭证键后的任意值（`key: value` / `key=value`） | 值替换为 `******` | `password: ******` |

> **顺序依赖：** 身份证（`ID_CARD`）必须先于银行卡（`BANK_CARD`），否则卡号规则会先破坏身份证的日期位；
> `CREDENTIAL_BEARER` / `CREDENTIAL_JSON` 必须先于通用 `CREDENTIAL`，避免二次脱敏噪声。
> 旧的中文姓名规则 `NAME` 已移除（全文正则误伤率高）。
>
> **CREDENTIAL 的匹配粒度：** 值部分为贪婪的 `\S+`，一直匹配到下一个空白字符为止——
> 引号、逗号、分号等分隔符会一并计入值被打码（宁可多脱敏、不漏脱敏）。

**性能保护机制：**

- 超过 8192 字符（`MAX_MASK_LENGTH`）的文本：仅跳过「无界」规则以防 ReDoS，
  当前内置规则均声明为 `bounded=true`（线性匹配），超长文本仍会被完整脱敏
- 快速预判：文本中不含数字、`@`、且不含凭证键（password/secret/token/...）时直接跳过

### 自定义脱敏策略

实现 `LogMasker` 接口并注册为 Spring Bean 即可替换默认脱敏逻辑：

```java
@Component
public class CustomLogMasker implements LogMasker {

    @Override
    public String mask(String text) {
        if (text == null) {
            return null;
        }
        // 自定义脱敏逻辑，例如基于 JSON 字段名匹配
        return text.replaceAll("\"password\"\\s*:\\s*\"[^\"]+\"", "\"password\":\"***\"");
    }
}
```

> 注册自定义 `LogMasker` Bean 后，`MaskingConverter` 会自动从 Spring 容器获取并使用，
> 框架的 `DefaultLogMasker` 因 `@ConditionalOnMissingBean` 自动让路。

#### 选择性启用脱敏

只启用部分内置规则（如仅脱敏手机号和邮箱）：

```java
@Configuration
public class MyLoggingConfig {

    @Bean
    public LogMasker logMasker() {
        return new DefaultLogMasker(List.of(MaskPattern.PHONE, MaskPattern.EMAIL));
    }
}
```

### 配置 Logback

#### 关键配置项

| 配置项 | 说明 | 必须配置 |
|--------|------|---------|
| `conversionRule`（`maskMsg`） | 注册消息脱敏 Converter | 是（使用 `%maskMsg` 时） |
| `conversionRule`（`maskEx`） | 注册异常栈脱敏 Converter | 是（使用 `%maskEx` 时） |
| `%X{traceId:-}` | 输出 TraceId，`:-` 表示无值时输出空字符串 | 是（需要 TraceId 时） |
| `%maskMsg` | 替代 `%msg`，输出脱敏后的日志消息 | 是（需要脱敏消息时） |
| `%maskEx` | 替代 `%ex`，输出脱敏后的异常栈 | 按需（需要脱敏异常栈时） |

#### 完整配置示例（含文件滚动）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <conversionRule conversionWord="maskMsg" class="cn.nextdev.lightboot.logging.mask.MaskingConverter"/>
    <conversionRule conversionWord="maskEx" class="cn.nextdev.lightboot.logging.mask.MaskingThrowableConverter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{40} - %maskMsg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/application.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/application.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>3GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{traceId:-}] %-5level %logger{40} - %maskMsg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 异步线程 MDC 传播

`@Async` 方法在新线程执行，MDC 默认不会传播。需配合 web 模块 `AsyncConfig` 使用。

**使用 `lightboot-web` 的项目：** web 模块的 `AsyncConfig` 会自动组合
`logging` 模块的 `mdcTaskDecorator`（MDC 传播）与请求上下文传播，无需额外配置。

```java
@Service
public class NotificationService {

    @Async("taskExecutor")
    public void sendNotification(String message) {
        // TraceId 自动传播到异步线程
        log.info("发送通知: {}", message);
    }
}
```

**自定义线程池：** 直接注入 logging 模块的 `mdcTaskDecorator` Bean 复用同一传播逻辑：

```java
@Bean
public ThreadPoolTaskExecutor myExecutor(MdcTaskDecorator mdcTaskDecorator) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // ...
    executor.setTaskDecorator(mdcTaskDecorator);  // MDC 传播 + 执行后恢复子线程原有 MDC
    return executor;
}
```

**使用 `CompletableFuture` 的场景：** 需要手动包装 MDC 传播：

```java
import org.slf4j.MDC;
import java.util.Map;

public class MdcWrapper {
    public static Runnable wrap(Runnable task) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (mdcContext != null) {
                    MDC.setContextMap(mdcContext);
                }
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}

// 使用示例
CompletableFuture.runAsync(MdcWrapper.wrap(() -> {
    log.info("异步任务中的日志，TraceId 自动传播");
}));
```

### 兼容性说明

| 环境 | 兼容性 | 说明 |
|------|--------|------|
| Spring MVC（Servlet） | 支持 | TraceFilter 和 RequestLogFilter 基于 `OncePerRequestFilter`（jakarta.servlet） |
| Spring WebFlux（Reactive） | 不支持 | 需使用 `WebFilter` + Reactor Context，暂不支持 |
| 非 Web 应用 | 部分支持 | TraceFilter/RequestLogFilter/HttpMetricsFilter 不加载，脱敏仍可用 |
| Spring Boot 4.x | 支持 | 使用 `jakarta.servlet` + Jackson 3 |
| Spring Boot 3.x / 2.x | 不兼容 | 基于 Spring Boot 4 自动配置机制与 Jackson 3 构建 |

### 常见问题

#### 日志中没有 TraceId

检查 `logback-spring.xml` 的 pattern 中是否包含 `%X{traceId:-}`。`:-` 表示无值时输出空字符串，避免输出 `null`。

#### 脱敏没有生效

1. 确认 `logback-spring.xml` 中已注册 `conversionRule` 并使用 `%maskMsg`
2. 确认 `light-boot.logging.mask.enabled` 未设置为 `false`

#### 异步方法中 TraceId 丢失

确保使用 `lightboot-web` 提供的 `taskExecutor`（内置 MDC 传播）。如果使用自定义线程池或
`CompletableFuture`，需手动包装 MDC 传播（参见 [异步线程 MDC 传播](#异步线程-mdc-传播)）。

#### 定时任务没有 TraceId

确保项目中引入了 AOP 依赖（Spring Boot 4 中为 `spring-boot-starter-aspectj`）。
定时任务的 TraceId 由 AOP 切面注入，无 AOP 依赖时切面不加载。

#### 不想记录某些路径的请求日志

在 `application.yml` 中配置排除路径（支持 Ant 风格通配符）：

```yaml
light-boot:
  logging:
    request:
      exclude-paths:
        - /actuator/**
        - /static/**
        - /favicon.ico
        - /health
```

#### 关闭整个日志模块

```yaml
light-boot:
  logging:
    enabled: false
```

#### 只关闭脱敏，保留 TraceId 和请求日志

```yaml
light-boot:
  logging:
    mask:
      enabled: false
```

同时将 `logback-spring.xml` 中的 `%maskMsg` 改回 `%msg`。

### 日志模块配置属性一览

```yaml
light-boot:
  logging:
    enabled: true                    # 是否启用日志模块（含 HTTP 指标采集）
    trace:
      enabled: true                  # 是否启用 TraceId 过滤器
      header-name: "X-Trace-Id"      # TraceId 请求头名称
    request:
      enabled: true                  # 是否启用请求日志
      slow-threshold-ms: 1000        # 慢请求阈值（毫秒），达到或超过阈值记 WARN + [SLOW]；0/负值禁用
      exclude-paths:                 # 排除路径（Ant 风格）
        - "/actuator/**"
        - "/favicon.ico"
      trusted-proxies: []            # 受信代理 CIDR 列表，仅这些来源的 X-Forwarded-For 等头才被采信（默认空=不信任任何代理）
    mask:
      enabled: true                  # 是否启用日志脱敏
      wired: false                   # 是否已在 logback-spring.xml 完成 %maskMsg/%maskEx 接线；置 true 抑制 MaskingStartupLogger 启动 WARN（框架不检测运行时 layout）
```

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | `boolean` | `true` | 是否启用日志模块（含 HTTP 指标） |
| `trace.enabled` | `boolean` | `true` | 是否启用 TraceId 过滤器 |
| `trace.header-name` | `String` | `X-Trace-Id` | TraceId 请求头名称，用于上下游透传 |
| `request.enabled` | `boolean` | `true` | 是否启用 HTTP 请求日志 |
| `request.slow-threshold-ms` | `long` | `1000` | 慢请求阈值（毫秒）。请求耗时 >= 阈值时日志级别提升为 WARN 并追加 `[SLOW]` 标记；设为 0 或负值禁用 |
| `request.exclude-paths` | `List<String>` | `["/actuator/**", "/favicon.ico"]` | 排除路径列表（Ant 风格），匹配的路径不记录请求日志 |
| `request.trusted-proxies` | `List<String>` | `[]` | 受信代理 CIDR 列表（如 `10.0.0.0/8`）；仅当 `remoteAddr` 命中此处时才解析 `X-Forwarded-For`/`X-Real-IP` 等转发头，默认空表示不信任任何代理 |
| `mask.enabled` | `boolean` | `true` | 是否启用日志脱敏 |
| `mask.wired` | `boolean` | `false` | 是否已在 `logback-spring.xml` 完成 `%maskMsg`/`%maskEx` 接线；置 `true` 声明已完成接线并抑制启动 WARN |

---

## redis — Redis 模块

### 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-redis</artifactId>
</dependency>
```

在 `application.yml` 中配置 Redis 连接信息：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
```

### 自动配置加载顺序

Redis 模块通过 Spring Boot 自动配置机制装配，按以下顺序加载：

```text
1. RedisTemplateAutoConfiguration — 定制 RedisTemplate（Key: String, Value: JSON）
                                    + Lettuce 命令超时/连接超时/连接池定制器
2. RedisCachingAutoConfiguration  — @EnableCaching（幂等开启 Spring Cache 注解支持）
3. RedisCacheAutoConfiguration    — RedisCacheManager + CacheErrorHandler（经 CachingConfigurer 接入缓存拦截链）
4. RedisServiceAutoConfiguration  — RedisService / SequenceService
                                    （fault-tolerant=true 时装配 FaultTolerantRedisService）
5. RedisObservabilityAutoConfiguration — RedisMetricsRecorder（需 actuator）
```

**序列化策略：** `RedisTemplate` 和 `RedisCacheManager` 共享同一个 `RedisJsonSerializerFactory`
（Jackson 3），确保数据读写使用完全一致的序列化方式。序列化细节如下：

- **Key / HashKey** — `StringRedisSerializer`（UTF-8），便于 Redis CLI 直接查看
- **Value / HashValue** — `GenericJacksonJsonRedisSerializer`（Jackson 3，带类型信息，支持多态对象的存储与反序列化）

> **反序列化安全：** 框架通过 `BasicPolymorphicTypeValidator` 收紧反序列化白名单。
> 默认仅放行常用 JDK 具体类型（`String`/`Number`/`Boolean`/`Character` 及 `Collection`/`Map` 的子类型）
> 与 `cn.nextdev.lightboot.` 包下的子类型；**`java.util.Date`、枚举等默认不在白名单内**，
> 业务类型需通过 `light-boot.redis.serializer.base-packages` 配置额外的白名单包路径，
> 否则反序列化时被类型校验器拒绝。
>
> **注意：** 序列化使用 `DefaultTyping.NON_FINAL`，只为非 final 类型写入 `@class` 类型标识。
> `java.time` 的类型均为 final，不写类型标识、也不查询白名单，将其加入 `base-packages`
> **无法**使其带类型往返（读回为字符串）；此类值建议存入自定义 DTO 序列化，或配置自定义（反）序列化器。

**条件装配：**

- 整个模块可通过 `light-boot.redis.enabled=false` 关闭
- `@EnableCaching` 是幂等开启，业务方自定义 `CacheManager` 时仍会被自动拾取使用

**超时与连接池：**

- `command-timeout-millis`（默认 3000）通过 Lettuce `ClientConfiguration` 定制器注入，覆盖 `spring.data.redis.timeout`
- `connect-timeout-millis`（默认 2000）经 `ClientOptions`/`SocketOptions` 注入，与命令超时是两条独立路径
- 连接池（`pool.*`）仅在 Boot 构建「带池」客户端配置时生效——需 `spring.data.redis.lettuce.pool.enabled=true`
  （或 commons-pool2 在 classpath 且未显式关闭）

### Key 定义规范 RedisKeyDefinition

所有 Redis Key 通过实现 `RedisKeyDefinition` 接口的枚举来管理，统一规范 Key 前缀和超时时间。

```java
public enum RedisKeys implements RedisKeyDefinition {

    // 格式：Key 前缀, 超时时间（分钟）
    AUTH_SMS_CODE("auth:sms:code:", 10L),           // 短信验证码，10 分钟
    USER_TOKEN("user:token:", 120L),                // 用户令牌，120 分钟
    ORDER_LOCK("order:lock:", 30L),                 // 订单锁
    ;

    private final String prefix;
    private final Long timeout;

    RedisKeys(String prefix, Long timeout) {
        this.prefix = prefix;
        this.timeout = timeout;
    }

    @Override
    public String getPrefix() { return prefix; }

    @Override
    public Long getTimeout() { return timeout; }

    // buildKey(String suffix) 由接口 default 方法提供
}
```

**Key 构建示例：**

```java
// RedisKeys.AUTH_SMS_CODE.getPrefix() + "13800138000"
String key = RedisKeys.AUTH_SMS_CODE.buildKey("13800138000");
// → "auth:sms:code:13800138000"
```

**完整 Key 结构：** `{全局前缀}{Key 前缀}{后缀}`，例如 `light-boot:auth:sms:code:13800138000`

> 全局前缀由 `light-boot.redis.key-prefix` 配置（默认 `light-boot:`），
> 缓存 Key 前缀由 `light-boot.cache.redis.key-prefix` 单独控制。

### 统一操作服务 RedisService

`RedisService` 封装了常用的 Redis 数据结构操作，所有方法均接受 `RedisKeyDefinition` 参数。内部自动拼接全局前缀。

> **扩展访问：** `RedisService` 通过 `@Getter` 暴露了 `redisTemplate` 字段
> （`RedisTemplate<String, Object>`，方法名 `getRedisTemplate()`），
> 可直接用于调用未封装的高级 Redis 命令。

#### String 操作

```java
@Autowired
private RedisService redisService;

// 设置 String 值（使用 KeyDefinition 中定义的超时，单位：分钟）
redisService.setString(RedisKeys.AUTH_SMS_CODE, "13800138000", "123456");

// 设置 String 值（自定义超时）
redisService.setString(RedisKeys.AUTH_SMS_CODE, "13800138000", "123456", 5, TimeUnit.MINUTES);

// 设置 String 值（永不过期）
redisService.setStringWithoutExpire(RedisKeys.AUTH_SMS_CODE, "key", "value");

// 仅当 Key 不存在时设置（SET NX）
Boolean created = redisService.setStringIfAbsent(
    RedisKeys.AUTH_SMS_CODE, "13800138000", "123456", 10, TimeUnit.MINUTES
);

// 获取 String 值
String code = redisService.getString(RedisKeys.AUTH_SMS_CODE, "13800138000");

// 获取 Object 值（JSON 反序列化）
Object obj = redisService.getObject(RedisKeys.USER_TOKEN, "token123");

// 获取 Object 值并转换类型（类型不匹配时自愈：告警 + 删除脏 Key + 返回 null）
UserDTO user = redisService.getObject(RedisKeys.USER_TOKEN, "token123", UserDTO.class);

// 设置 Object 值（JSON 序列化）
redisService.setObject(RedisKeys.USER_TOKEN, "token123", userDTO);
redisService.setObject(RedisKeys.USER_TOKEN, "token123", userDTO, 2, TimeUnit.HOURS);
redisService.setObjectWithoutExpire(RedisKeys.USER_TOKEN, "token123", userDTO);
```

#### Hash 操作

```java
// 写入单个字段
redisService.put(RedisKeys.USER_TOKEN, "user1", "name", "张三");

// 批量写入
Map<String, String> map = Map.of("name", "张三", "age", "25");
redisService.putAll(RedisKeys.USER_TOKEN, "user1", map);

// 读取单个字段
Object name = redisService.get(RedisKeys.USER_TOKEN, "user1", "name");

// 获取全部字段
Map<Object, Object> entries = redisService.getHashEntries(RedisKeys.USER_TOKEN, "user1");

// 删除字段
redisService.hashDelete(RedisKeys.USER_TOKEN, "user1", "name", "age");

// 判断字段是否存在
Boolean exists = redisService.hasKey(RedisKeys.USER_TOKEN, "user1", "name");
```

#### List 操作

```java
// 左推入 / 右推入
redisService.leftPush(RedisKeys.USER_TOKEN, "queue", "item1");
redisService.rightPush(RedisKeys.USER_TOKEN, "queue", "item2");

// 左弹出 / 右弹出（破坏性读：LPOP/RPOP，元素被移除）
Object left = redisService.leftPop(RedisKeys.USER_TOKEN, "queue");
Object right = redisService.rightPop(RedisKeys.USER_TOKEN, "queue");

// 获取范围元素（end = -1 表示到最后一个元素）
List<Object> items = redisService.listRange(RedisKeys.USER_TOKEN, "queue", 0, -1);

// 获取列表长度（Key 不存在时返回 0）
Long size = redisService.listSize(RedisKeys.USER_TOKEN, "queue");

// 裁剪列表（只保留索引 0-99 的元素）
redisService.listTrim(RedisKeys.USER_TOKEN, "queue", 0, 99);
```

#### Set 操作

```java
// 添加元素
redisService.setAdd(RedisKeys.USER_TOKEN, "tags", "java", "spring");

// 移除元素
redisService.setRemove(RedisKeys.USER_TOKEN, "tags", "java");

// 获取所有元素
Set<Object> members = redisService.setMembers(RedisKeys.USER_TOKEN, "tags");

// 判断元素是否存在
Boolean isMember = redisService.setIsMember(RedisKeys.USER_TOKEN, "tags", "spring");

// 获取元素数量（Key 不存在时返回 0）
Long count = redisService.setSize(RedisKeys.USER_TOKEN, "tags");
```

#### Sorted Set 操作

```java
// 添加元素（带分数）
redisService.zSetAdd(RedisKeys.USER_TOKEN, "leaderboard", "player1", 100.0);

// 批量添加元素
Set<ZSetOperations.TypedTuple<Object>> tuples = new HashSet<>();
tuples.add(new DefaultTypedTuple<>("player1", 100.0));
tuples.add(new DefaultTypedTuple<>("player2", 95.0));
redisService.zSetAdd(RedisKeys.USER_TOKEN, "leaderboard", tuples);

// 移除元素
redisService.zSetRemove(RedisKeys.USER_TOKEN, "leaderboard", "player1");

// 按排名获取（升序）
Set<Object> top10 = redisService.zSetRange(RedisKeys.USER_TOKEN, "leaderboard", 0, 9);

// 按排名获取（降序）
Set<Object> bottom10 = redisService.zSetReverseRange(RedisKeys.USER_TOKEN, "leaderboard", 0, 9);

// 按分数范围获取
Set<Object> highScores = redisService.zSetRangeByScore(
    RedisKeys.USER_TOKEN, "leaderboard", 80.0, 100.0
);

// 获取元素分数
Double score = redisService.zSetScore(RedisKeys.USER_TOKEN, "leaderboard", "player1");

// 获取元素排名（0-based，升序）
Long rank = redisService.zSetRank(RedisKeys.USER_TOKEN, "leaderboard", "player1");

// 获取元素数量（Key 不存在时返回 0）
Long zsetSize = redisService.zSetSize(RedisKeys.USER_TOKEN, "leaderboard");
```

#### 通用操作

```java
// 删除 Key
redisService.delete(RedisKeys.AUTH_SMS_CODE, "13800138000");

// 批量删除
redisService.delete(RedisKeys.AUTH_SMS_CODE, List.of("13800138000", "13900139000"));

// 设置过期时间（使用 KeyDefinition 定义的超时，单位：分钟）
redisService.expire(RedisKeys.USER_TOKEN, "token123");

// 设置过期时间（自定义）
redisService.expire(RedisKeys.USER_TOKEN, "token123", 30, TimeUnit.MINUTES);

// 设置绝对过期时间（指定到期时刻）
redisService.expireAt(RedisKeys.USER_TOKEN, "token123", Instant.parse("2026-12-31T23:59:59Z"));

// 自增 / 自减
Long val = redisService.increment(RedisKeys.USER_TOKEN, "counter");
Long val2 = redisService.increment(RedisKeys.USER_TOKEN, "counter", 5);
Long val3 = redisService.decrement(RedisKeys.USER_TOKEN, "counter");

// 判断 Key 是否存在
Boolean exists = redisService.hasKey(RedisKeys.USER_TOKEN, "token123");
```

#### 指标埋点范围

引入 actuator 后，**写操作**（set/hset/lpush/sadd/zadd/incr/decr/delete/expire 等所有变更类）
与**重读操作**（get/getObject/hgetall/smembers/lrange/zrange/zrangebyscore 等可能拉大 payload 的读取）
自动埋点到 `light-boot.redis.operations`；轻量点查询
（`exists`/`hexists`/`sismember`/`zscore`/`zrank`/`scard`/`llen`/`zcard`/`hget`）不埋点——
埋点开销可能接近命令本身，避免测量干扰。未引入 actuator 时埋点为无操作，不影响业务。

### 容错模式 FaultTolerantRedisService

设置 `light-boot.redis.fault-tolerant=true` 后，自动装配的 `RedisService` Bean 替换为
`FaultTolerantRedisService`。它仅对**读操作**在 **`DataAccessException`**（连接失败、超时等
Spring 数据访问异常）时降级——返回 `null` / 空集合 / `false`，仅记录 WARN 日志，不打断业务；
**写操作（set/put/push/delete/expire/increment 等）仍抛异常**，避免静默吞掉写失败造成数据不一致。
默认 `false`（关闭），因为容错会掩盖 Redis 故障，应在知晓代价后开启。

> **捕获范围：** 仅捕获 `DataAccessException`；值反序列化失败（脏数据/类型不匹配）等
> 非数据访问异常不在容错范围内，仍会上抛。

```yaml
light-boot:
  redis:
    fault-tolerant: false   # 默认关闭；开启后读操作降级，写操作仍抛异常
```

| 容错读方法 | 降级返回值 |
|-----------|-----------|
| `getString` / `getObject` / Hash `get` / `listSize` / `setSize` / `zSetScore` / `zSetRank` / `zSetSize` | `null`（size 类区别于「Key 不存在」的 `0`） |
| `getHashEntries` / `listRange` / `setMembers` / `zSetRange*` | 空集合（`Map.of()` / `List.of()` / `Set.of()`） |
| `hasKey` / `setIsMember` | `false`（调用方无法区分「不存在」与「故障降级」） |

> **⚠ 破坏性读警告：** `leftPop`/`rightPop` 是破坏性操作（LPOP/RPOP），容错降级返回 `null` 时
> Redis 端可能已成功弹出元素——调用方会误以为「列表为空」。**不容忍消息丢失的队列消费场景**
> 请勿开启容错（`fault-tolerant=false`），让 pop 异常上抛触发上层重投递/补偿。

### 序列号生成 SequenceService

基于 Redis Lua 脚本（单脚本原子完成 `INCR` + 当日过期）的全局唯一序列号生成器，
格式为 `{prefix}{yyyyMMdd}{序号}`，每日自动重置。

```java
@Autowired
private SequenceService sequenceService;

// 生成当日序列号
String orderNo = sequenceService.generate("ORD");
// → "ORD202605110001"

String payNo = sequenceService.generate("PAY");
// → "PAY202605110001"

// 生成指定日期的序列号
String no = sequenceService.generate("ORD", LocalDate.of(2026, 5, 1));
// → "ORD202605010001"
```

**格式规则：**

| 部分 | 示例 | 说明 |
|------|------|------|
| 前缀 | `ORD` | 业务自定义 |
| 日期 | `20260511` | yyyyMMdd 格式 |
| 序号 | `0001` | 初始 4 位，超过 9999 后自动扩展位数 |

**特点：**

- 每日基于不同 Redis Key（`{全局前缀}global:sequence:{业务前缀}:{日期}`，如
  `light-boot:global:sequence:ORD:20260511`——业务前缀与日期之间有 `:` 分隔符），序列号从 1 开始
- Lua 脚本每次调用「无条件」把 Key 过期时间重置为当天结束时刻（绝对时间戳幂等），
  在时钟回拨/AOF 重放/备份恢复后仍能正确重设过期
- `INCR` 原子操作，保证全局唯一且严格递增
- 时区固定为 `Asia/Shanghai`

**失败语义（SequenceGenerationException）：** 当 Redis 连接失败或脚本异常时，`generate(...)`
抛出专用的 `SequenceGenerationException`（携带 `prefix` 与 `date` 上下文，错误码 `50000`）。
该异常**继承 `ApiException`**，引入 `lightboot-web` 后由 `GlobalExceptionHandler` 兜底捕获，
转为 `Result.failed(50000, ...)` 并映射为 **HTTP 500**；未引入 web 模块则按普通 `RuntimeException` 上抛。

### 缓存配置

Redis 模块自动配置 `RedisCacheManager`，支持 Spring Cache 注解（`@Cacheable`、`@CacheEvict`、`@CachePut` 等）。

**缓存 Key 前缀说明：** 缓存 Key 使用独立的 `light-boot.cache.redis.*` 配置（默认 `app:`），
与 RedisService/SequenceService 使用的 `light-boot.redis.key-prefix`（默认 `light-boot:`）分离，互不影响。

```yaml
light-boot:
  cache:
    redis:
      key-prefix: "app:"           # 缓存 Key 前缀
      time-to-live: 3600           # 默认 TTL（秒）
      cache-null-values: false     # 是否缓存空值（防穿透）
      use-key-prefix: true         # 是否启用 Key 前缀（false 时完全禁用前缀，Key 仅由缓存 key 组成，不同缓存同名 key 会互相覆盖）
      caches:                      # 按缓存名独立配置
        users:
          time-to-live: 1800       # 用户缓存 30 分钟
        products:
          time-to-live: 7200       # 商品缓存 2 小时
```

**缓存配置属性一览（`light-boot.cache.redis.*`）：**

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `key-prefix` | `String` | `app:` | 缓存 Key 前缀，用于共享 Redis 中的命名空间隔离（生成 `app:users::1` 形式） |
| `time-to-live` | `Integer` | `3600` | 默认缓存过期时间（秒） |
| `cache-null-values` | `boolean` | `false` | 是否缓存 null 值，开启可防缓存穿透但增加内存占用 |
| `use-key-prefix` | `boolean` | `true` | 是否启用 Key 前缀（`false` 时完全禁用前缀，Key 仅由缓存 key 组成、不含 cacheName 与 `::`，**不同缓存的同名 key 会互相覆盖**，共享 Redis 场景慎用）；共享 Redis 时建议开启 |
| `caches` | `Map` | `{}` | 按缓存名独立配置 |
| `caches.<name>.time-to-live` | `Integer` | — | 特定缓存的过期时间（秒），覆盖默认值 |

```java
@Service
public class UserService {

    @Cacheable(value = "users", key = "#id")
    public User getById(Long id) {
        return userMapper.selectById(id);
    }

    @CacheEvict(value = "users", key = "#id")
    public void delete(Long id) {
        userMapper.deleteById(id);
    }
}
```

**缓存异常处理：** 框架内置 `CacheErrorHandler` 并通过 `CachingConfigurer` 将其接入 Spring 缓存拦截链
（裸 `CacheErrorHandler` Bean 不会被框架消费），Redis 异常时仅记录 ERROR 日志不抛出，防止缓存层故障影响主业务。
引入方自定义了 `CachingConfigurer` 时，框架的配置自动退避。

---

## redis-lock — Redis 锁模块

基于 [Redisson](https://redisson.org/) 的可重入分布式锁模块，独立于 `lightboot-redis`，按需引入。
引入后自动装配 `RedissonClient`（复用 `spring.data.redis` 配置）和 `RedissonDistributedLock`。

> **说明：** 引入 `lightboot-redis-lock` 即自动带入 `redisson` 与 `spring-boot-starter-data-redis`（版本由 BOM 管理，
> Redisson 4.7.0）。

### 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-redis-lock</artifactId>
</dependency>
```

### 自动配置加载顺序

```text
1. RedisTemplateAutoConfiguration（redis 模块，提供 RedisProperties 与全局 Key 前缀）
    ↓
2. RedissonAutoConfiguration       — 装配 RedissonClient（单机/哨兵/集群）与 RedissonDistributedLock
```

Redisson 连接参数复用 Spring Boot 标准 `spring.data.redis.*`（host/port/password/database/sentinel/cluster），
支持单机、哨兵、集群三种模式：

- `spring.data.redis.ssl=true` 时自动使用 `rediss://` 地址
- 哨兵模式使用 `spring.data.redis.sentinel.master` + `sentinel.nodes`；集群模式逐节点注册 `cluster.nodes`
- 用户名与密码在 Redisson `Config` 级别统一设置，对三种模式同时生效
- 连接池等参数沿用 Redisson 默认值；如需自定义，可直接注册自己的 `RedissonClient` Bean，框架的 `@ConditionalOnMissingBean` 会自动让位

**条件装配：**

- 整个模块可通过 `light-boot.redis.redisson.enabled=false` 关闭
- 锁封装可通过 `light-boot.redis.redisson.lock-enabled=false` 单独关闭
  （仅保留 `RedissonClient`，供业务直接使用 Redisson 全部能力）

### 分布式锁 RedissonDistributedLock

`RedissonDistributedLock` 是对 Redisson `RLock` 的薄封装，天然支持**可重入**（同一线程可多次获取）
与**看门狗自动续期**（默认 30s 租期，看门狗每约租期 1/3 即约 10s 续期一次，长任务不会因过期丢锁）。
所有加锁方式均以 `leaseTime = -1` 抢锁，由 Redisson 看门狗自动续期。
锁 Key 自动加全局前缀（`light-boot.redis.key-prefix`）。

```java
@Resource
private RedissonDistributedLock distributedLock;

// 推荐：自动加锁 + try-finally 解锁（看门狗续期），supplier 抛异常原样上抛不吞异常
String result = distributedLock.executeWithLock("order:create:" + userId, () -> doBusiness());

// 带等待时间的尝试执行：最多等 5s，未抢到返回 null（业务不执行）；线程被中断同样返回 null
String r2 = distributedLock.executeWithTryLock("order:pay:123", 5, TimeUnit.SECONDS, () -> pay());
if (r2 == null) {
    // 抢锁失败或被中断
}

// 无返回值版本
boolean locked = distributedLock.executeWithTryLock("order:pay:123", 5, TimeUnit.SECONDS, () -> pay());

// 直接获取 RLock（需自行 lock/unlock，或查询 holdCount 等细粒度信息）
RLock lock = distributedLock.getLock("order:123");

// 也可以用 RedisKeyDefinition 构建 Key
RLock lock2 = distributedLock.getLock(OrderRedisKey.CREATE_LOCK, String.valueOf(userId));
```

**核心方法：**

| 方法 | 说明 |
|------|------|
| `executeWithLock(key, Supplier<T>)` | 自动 `lock()` + try-finally `unlock()`（推荐），supplier 抛异常原样上抛 |
| `executeWithLock(key, Runnable)` | 同上，无返回值 |
| `executeWithTryLock(key, waitTime, unit, Supplier<T>)` | 最多等待 `waitTime` 抢锁并执行；抢锁失败或被中断返回 `null`（中断时恢复中断标志） |
| `executeWithTryLock(key, waitTime, unit, Runnable)` | 同上，返回 `boolean`：抢到锁执行返回 `true`，失败或被中断返回 `false` |
| `getLock(key)` / `getLock(keyDef, suffix)` | 获取原始 `RLock`（自行控制锁生命周期） |
| `unlock(key)` | 解锁（仅当前线程持锁时才解，避免 `IllegalMonitorStateException`） |

> **看门狗说明：** 本封装不暴露 `leaseTime` 参数，所有加锁均启用 Redisson 看门狗
> （默认 30s 租期、每约 10s 续期）。如需固定租约、到期自动释放，请直接 `getLock(key)` 后
> 调用 Redisson 原生的 `tryLock(waitTime, leaseTime, unit)`。

### 配置属性一览

```yaml
light-boot:
  redis:
    redisson:
      enabled: true            # 是否启用 Redisson 自动装配（含 RedissonClient）
      lock-enabled: true       # 是否装配 RedissonDistributedLock
```

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `light-boot.redis.redisson.enabled` | `boolean` | `true` | 是否启用 Redisson 自动装配；关闭后即使引入模块也不创建 `RedissonClient` |
| `light-boot.redis.redisson.lock-enabled` | `boolean` | `true` | 是否装配 `RedissonDistributedLock`；设为 `false` 时仅创建 `RedissonClient` |

---

## 可观测性与 Actuator 集成

lightboot 遵循「克制」原则，**框架本身不引入 `spring-boot-starter-actuator`**，也不强制任何监控后端。
可观测性能力以「条件装配」的方式提供：引入方按需添加 actuator 即可激活指标采集，不添加则零开销、零依赖。

### 引入 Actuator 后获得什么

引入方在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

框架检测到类路径存在 `io.micrometer.core.instrument.MeterRegistry`（由 actuator 自动提供）后，
**自动装配**以下指标记录器（无 actuator 则完全不加载）：

| 指标名 | 类型 | 来源模块 | tag 维度 | 说明 |
|--------|------|---------|---------|------|
| `light-boot.http.requests` | Timer | logging | `method`、`status` | 每个 HTTP 请求的耗时，按 HTTP 方法与响应状态码聚合 |
| `light-boot.redis.operations` | Timer | redis | `operation`、`result` | Redis 操作耗时，按操作名（set/incr/delete 等）与结果（success/failure）聚合 |

### 指标 tag 设计原则

- **只用低基数维度作 tag**：`method`（GET/POST/…）、`status`（200/404/500/…）、`operation`、`result`，
  取值种类有限，不会导致指标时间序列爆炸。
- **不使用 URI 作为 tag**：URI 含路径变量（`/users/123`、`/orders/456`），基数极高，会撑爆监控系统。
  URI 维度的排查由**日志**（含 traceId）承担。
- **不把 traceId 塞进指标 tag**：traceId 每请求唯一，属于典型高基数维度。指标用于聚合统计，
  traceId 用于日志关联，二者职责分离。

### 端点暴露建议

actuator 默认仅暴露 `/actuator/health`。生产环境推荐配置：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info        # 最小集；需要在线查指标时追加 metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized # 避免泄露健康详情
```

---

## 统一超时配置建议

> **框架在 Redis 上提供了超短期默认值**（命令 3s / 连接 2s），并允许业务覆盖。线程池关闭等待时间可调。
> 阈值应**短于**上游调用方（网关/HTTP 客户端）的超时，避免请求在网关侧已超时而服务端仍在阻塞。

### Redis 超时（框架增强）

```yaml
light-boot:
  redis:
    command-timeout-millis: 3000   # 命令超时（毫秒），覆盖 spring.data.redis.timeout
    connect-timeout-millis: 2000   # 连接超时（毫秒），经 ClientOptions/SocketOptions 生效
    pool:                          # 连接池（需启用 Boot 的池开关 + commons-pool2）
      max-total: 16
      max-idle: 8
      min-idle: 2
      max-wait-millis: 2000        # 获取连接最大等待，避免池耗尽时无限阻塞
```

### 线程池优雅关闭

异步与定时任务线程池的关闭等待时间由 `await-termination-seconds` 控制（默认均 `60` 秒）：

- 短任务（通知、日志）建议 30-60s
- 长任务（批处理、大文件）建议 120-300s，确保关闭时不丢任务
- 配合 `allow-core-thread-timeout: true` 可在低峰期回收核心线程，节省资源

---

## 配置属性速查

所有框架配置统一使用 `light-boot` 前缀：

```yaml
light-boot:
  # ============ Web 模块 ============
  web:
    http-status:
      enabled: true              # 异常响应是否携带真实 HTTP 状态码；false 退回全 200

  # ============ 异步线程池 ============
  async:
    executor:
      core-pool-size: 10
      max-pool-size: 20
      queue-capacity: 500
      keep-alive-seconds: 60
      thread-name-prefix: "async-executor-"
      allow-core-thread-timeout: true
      await-termination-seconds: 60

  # ============ 定时任务线程池 ============
  scheduling:
    thread-pool:
      pool-size: 10
      thread-name-prefix: "scheduled-task-"
      wait-for-tasks-to-complete-on-shutdown: true
      await-termination-seconds: 60

  # ============ CORS 跨域 ============
  cors:
    enabled: false               # 默认 false，需显式开启
    allowed-origins: []          # 默认空，内部以 allowedOriginPatterns 注册，支持 * 通配
    allowed-methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
    allowed-headers: ["*"]
    exposed-headers: ["Content-Disposition", "Authorization"]
    allow-credentials: false
    max-age: 3600
    path-pattern: "/**"

  # ============ 日志模块 ============
  logging:
    enabled: true
    trace:
      enabled: true
      header-name: "X-Trace-Id"
    request:
      enabled: true
      slow-threshold-ms: 1000
      exclude-paths: ["/actuator/**", "/favicon.ico"]
      trusted-proxies: []
    mask:
      enabled: true
      wired: false

  # ============ Redis 模块 ============
  redis:
    enabled: true
    key-prefix: "light-boot:"    # Key 全局前缀（RedisService、SequenceService、分布式锁）
    fault-tolerant: false
    command-timeout-millis: 3000
    connect-timeout-millis: 2000
    pool:
      enabled: true
      max-total: 16
      max-idle: 8
      min-idle: 2
      max-wait-millis: 2000
    serializer:
      base-packages: []          # 反序列化白名单扩展（Date/枚举默认被拒，需在此追加；java.time 为 final 类，加白名单无效）
    redisson:
      enabled: true
      lock-enabled: true

  # ============ Redis 缓存 ============
  cache:
    redis:
      key-prefix: "app:"         # 缓存 Key 前缀（独立于 redis.key-prefix）
      time-to-live: 3600
      cache-null-values: false
      use-key-prefix: true
      caches:
        users:
          time-to-live: 1800

  # ============ 接口限流 ============
  ratelimit:
    enabled: true                # 仍需 Redis + AOP 到位才生效
    default-time: 1              # 必须为正数，非正数启动即失败
    default-count: 100           # 必须为正数，非正数启动即失败
    fail-open: true
    message: "请求过于频繁，请稍后重试"
```

---

## 依赖版本

| 依赖 | 版本 |
|------|------|
| Java | 21+ |
| Maven | 3.9+ |
| Spring Boot | 4.1.1 |
| Jackson | 3.x（由 Spring Boot BOM 管理） |
| Jakarta Validation API | 3.1.1 |
| Sa-Token | 1.46.0 |
| Commons Pool2 | 2.13.1 |
| Redisson | 4.7.0 |
| Lombok | 1.18.48 |
| jedis-mock | 1.1.19（仅测试用，不传递给使用方） |

> 更多设计与原理说明见 [`docs/`](docs/) 目录下各模块的 design / usage 文档。
