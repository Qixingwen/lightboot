# web 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+

---

## 定位

Web 层基础设施模块，提供 **全局异常处理**、**CORS 跨域**、**JSON 序列化**、**异步线程池** 和 **定时任务调度**。
引入依赖即生效，合理的默认值覆盖大多数场景。

依赖 core 模块（使用 `Result` / `ResultCode` / `ApiException`）。

---

## 模块结构

```text
cn.nextdev.lightboot.web/
├── config/
│   ├── WebAutoConfiguration.java      — 自动配置入口（装配 WebProperties + resolver + 3 个异常处理器）
│   ├── AsyncConfig.java               — @Async 线程池（可配置 + 上下文传播 + 拒绝策略）
│   ├── CorsConfig.java                — CORS 跨域（Spring MVC + Security 双配置）
│   ├── JacksonConfig.java             — JSON 全局格式（日期/时区）
│   ├── SchedulingConfig.java          — @Scheduled 调度线程池（错误处理 + 优雅关闭）
│   └── WebProperties.java             — light-boot.web 配置属性（http-status.enabled）
├── http/
│   └── HttpStatusCodeResolver.java    — Result.code → HTTP 状态码显式映射
└── handler/
    ├── GlobalExceptionHandler.java     — 通用全局异常处理（兜底）
    ├── DatabaseExceptionHandler.java   — 数据库异常处理（条件加载）
    └── SaTokenExceptionHandler.java    — SaToken 认证异常处理（条件加载）
```

**10 个 Java 文件** + `AutoConfiguration.imports`（注册 `WebAutoConfiguration`、`AsyncConfig`、`CorsConfig`、`JacksonConfig`、
`SchedulingConfig`）+ metadata。

---

## 核心设计

### 全局异常处理

三个 `@RestControllerAdvice` 按优先级处理：`SaTokenExceptionHandler` 与 `DatabaseExceptionHandler` 使用
`@Order(Ordered.HIGHEST_PRECEDENCE)` 优先命中；`GlobalExceptionHandler` 为 `@Order` 默认（`LOWEST_PRECEDENCE`）兜底。三者均由
`WebAutoConfiguration` 装配（自动配置 `@Bean`，无需组件扫描），统一通过 `HttpStatusCodeResolver` 设置 HTTP 状态码。

```text
请求 → 异常
         │
         ▼
    DatabaseExceptionHandler (@ConditionalOnClass("org.springframework.dao.DataAccessException"), HIGHEST_PRECEDENCE)
    命中？→ 返回 Result.failed()
         │
         ▼
    SaTokenExceptionHandler (@ConditionalOnClass("cn.dev33.satoken.exception.SaTokenException"), HIGHEST_PRECEDENCE)
    命中？→ 返回 Result.failed()
         │
         ▼
    GlobalExceptionHandler（兜底，默认优先级）
    返回 Result.failed()
```

> 上图是优先级关系的简化示意。Spring 的实际选择规则是：按 advice 优先级取**第一个含有匹配 `@ExceptionHandler` 的 advice**，
> 而非真正"链式依次尝试"。另外 `DatabaseExceptionHandler` 与 `SaTokenExceptionHandler` 同为 `HIGHEST_PRECEDENCE`，
> 二者之间无固定相对顺序——因两者处理的异常集合互不重叠（DAO 异常 vs SaToken 异常），该平局不产生实际影响。

**HTTP 状态码：** 三个处理器统一通过 `HttpStatusCodeResolver` 让响应携带与 `Result.code` 语义对应的真实 HTTP
状态码（400/401/403/404/409/413/429/500）；响应体仍是统一的 `Result`，业务结果由 `Result.code` 区分。
`light-boot.web.http-status.enabled=false` 时全部退回 200。解析规则见下文「HTTP 状态码解析」。

**GlobalExceptionHandler 异常映射：**

| 异常类型                                                | 错误码（Result.code） | HTTP | 说明                                                   |
|-----------------------------------------------------|------------------|------|------------------------------------------------------|
| `ApiException`（有 errorCode）                         | errorCode.code   | 按映射  | 业务异常，`Retry-After` 头在 `getRetryAfterSeconds()>0` 时写入 |
| `ApiException`（无 errorCode）                         | `-1`             | 500  | 降级使用 `e.getMessage()`                                |
| `MethodArgumentNotValidException` / `BindException` | 40000            | 400  | 参数校验失败（字段级错误拼接）                                      |
| `MethodArgumentTypeMismatchException`               | 40000            | 400  | 参数类型不匹配                                              |
| `MissingRequestHeaderException`                     | 40000            | 400  | 缺少必要请求头                                              |
| `MissingServletRequestParameterException`           | 40000            | 400  | 缺少必要请求参数                                             |
| `HttpMessageNotReadableException`                   | 40000            | 400  | 请求体解析失败（格式/类型不匹配）                                    |
| `HttpRequestMethodNotSupportedException`            | 40000            | 400  | HTTP 方法不支持                                           |
| `HttpMediaTypeNotSupportedException`                | 40000            | 400  | 媒体类型不支持                                              |
| `IllegalArgumentException`                          | 40000            | 400  | 非法参数                                                 |
| `NoHandlerFoundException`                           | 40400            | 404  | 处理器未找到                                               |
| `NoResourceFoundException`                          | 40400            | 404  | 静态资源未找到                                              |
| `MaxUploadSizeExceededException`                    | `41300`（FILE_TOO_LARGE） | 413  | 文件大小超限（WARN 日志，message 固定为「文件大小超过限制」）                    |
| `BadPaddingException`                               | `-1`             | 500  | 数据解密异常（ERROR 日志）                                     |
| `IOException`                                       | `-1`             | 500  | 文件读写异常（ERROR 日志）                                     |
| `IllegalStateException`                             | `-1`             | 500  | 系统状态异常（ERROR 日志）                                     |
| `NullPointerException`                              | 50000            | 500  | 空指针（ERROR 日志）                                        |
| `RuntimeException`                                  | `-1`             | 500  | 运行时异常兜底（ERROR 日志）                                    |
| `Exception`                                         | `-1`             | 500  | 顶层兜底（ERROR 日志）                                       |

> **说明：** `-1` 通过 `Result.failed(String message)` 返回（code 固定为 `ResultCode.FAILED = -1`），HTTP 由 resolver 解析（`-1`
> →500，`41300`→413 等）。`MethodArgumentNotValidException` 与 `BindException` 由同一个处理器统一捕获。

#### HTTP 状态码解析（HttpStatusCodeResolver）

`HttpStatusCodeResolver` 用一张**显式映射表**解析 `Result.code` → HTTP 状态码（不用前缀推断，避免歧义）。算法：
`enabled=false` 或 `code==0` 返回 200；其余查表，命中返回对应状态码，未命中返回 500。

| Result.code             | HTTP 状态码                  |
|-------------------------|---------------------------|
| `0`                     | 200 OK                    |
| `10001`（AUTH_FAILED）    | 401 Unauthorized          |
| `40000`（BAD_REQUEST）    | 400 Bad Request           |
| `40100`（UNAUTHORIZED）   | 401 Unauthorized          |
| `40300`（FORBIDDEN）      | 403 Forbidden             |
| `40400`（NOT_FOUND）      | 404 Not Found             |
| `40900`（CONFLICT）       | 409 Conflict              |
| `41300`（FILE_TOO_LARGE） | 413 Content Too Large     |
| `42901`（RATE_LIMIT）     | 429 Too Many Requests     |
| `50000`（INTERNAL_ERROR） | 500 Internal Server Error |
| `-1` / 其他               | 500 Internal Server Error |

**DatabaseExceptionHandler：**

| 异常                                | 用户看到的提示        | Result.code / HTTP        |
|-----------------------------------|----------------|---------------------------|
| `DuplicateKeyException`           | 数据已存在，唯一约束冲突   | `CONFLICT` 40900 / 409    |
| `DataIntegrityViolationException` | 数据完整性约束违反，操作失败 | `BAD_REQUEST` 40000 / 400 |
| `DataAccessException`             | 数据库操作异常        | `-1` / 500                |

隐藏底层 SQL 信息，防止信息泄露。条件：`@ConditionalOnClass("org.springframework.dao.DataAccessException")`（需 spring-tx
依赖）。

**SaTokenExceptionHandler：**

| 异常                                            | 错误码 / HTTP  | 提示                                     |
|-----------------------------------------------|-------------|----------------------------------------|
| `NotLoginException`                           | 40100 / 401 | 登录会话失效（`Result.unauthorized()`）        |
| `NotPermissionException` / `NotRoleException` | 40300 / 403 | 没有相关权限（`Result.forbidden()`）           |
| `SaTokenContextException`                     | -1 / 500    | 认证上下文异常，请检查系统配置                        |
| `SaTokenException`（兜底）                        | 40100 / 401 | 其他 SaToken 异常（`Result.unauthorized()`） |

条件：`@ConditionalOnClass("cn.dev33.satoken.exception.SaTokenException")`（需 sa-token-core 依赖）。

### AsyncConfig — 异步线程池

`@AutoConfiguration` + `@EnableAsync`，注册名为 `taskExecutor` 的 `ThreadPoolTaskExecutor`。替代 `@Async` 缺省时的执行器——
即每任务新建线程的 `SimpleAsyncTaskExecutor`（无任何 `TaskExecutor` Bean 时），或 Spring Boot 默认提供的
`applicationTaskExecutor`，并非"单线程执行器"。

| 参数                        | 默认值  |
|---------------------------|------|
| `corePoolSize`            | 10   |
| `maxPoolSize`             | 20   |
| `queueCapacity`           | 500  |
| `keepAliveSeconds`        | 60   |
| `allowCoreThreadTimeOut`  | true |
| `awaitTerminationSeconds` | 60   |

特性：

- **上下文传播**：组合 decorator `mdcTaskDecorator.decorate(wrapWithContext(original))`，两者各司其职——
  `wrapWithContext()` 只传播 `RequestAttributes`，MDC（含 TraceId）由 logging 模块的 `MdcTaskDecorator` 传播。
  构造时内层先捕获 RequestAttributes 快照、外层再捕获 MDC 快照；执行时外层先设 MDC、内层再设 RequestAttributes（finally 中反向恢复）
- **MDC 装饰器可选**：`MdcTaskDecorator` 经 `ObjectProvider` 注入；logging 模块未装配（无该 Bean）时退化为仅传播 `RequestAttributes`
- **自定义拒绝策略**：`RejectedExecutionHandlerWithLogging` 记录 WARN 日志后采用 **Caller-Runs**（在调用者线程中执行任务，不丢弃、不抛异常）
- **自定义线程工厂**：`TaskThreadFactory` 设置线程名（非守护线程）
- **未捕获异常兜底**：实现 `AsyncConfigurer#getAsyncUncaughtExceptionHandler`，`void` 返回值的 `@Async` 方法抛出异常时记录 ERROR 日志
- **优雅关闭**：`waitForTasksToCompleteOnShutdown=true`

### SchedulingConfig — 定时任务线程池

`@AutoConfiguration` + `@EnableScheduling`，实现 `SchedulingConfigurer` 接口。替代 Spring 默认单线程调度器。

| 参数                                 | 默认值  |
|------------------------------------|------|
| `poolSize`                         | 10   |
| `threadNamePrefix`                 | `scheduled-task-` |
| `waitForTasksToCompleteOnShutdown` | true |
| `awaitTerminationSeconds`          | 60   |

特性：

- 多个定时任务可并行执行
- `configureTasks()` 经 `ObjectProvider` 复用容器中的 `taskScheduler` Bean（单实例；`@AutoConfiguration` 为
  `proxyBeanMethods=false`，不能直接调用 `taskScheduler()`，否则会 new 出第二个非托管实例）
- `SchedulingErrorHandler` 三类分支：`RejectedExecutionException` → ERROR、`InterruptedException` → WARN、
  其他异常 → ERROR（含堆栈）；均不中断调度器
- `@PreDestroy` 优雅关闭

### CorsConfig — CORS 跨域

`@AutoConfiguration`，注册配置属性 Bean 及两个 CORS Bean（后者受 `light-boot.cors.enabled` 控制）：

| Bean                      | 类型                 | 条件                       | 说明                                               |
|---------------------------|--------------------|--------------------------|--------------------------------------------------|
| `corsProperties`          | `CorsProperties`   | 始终                       | 配置属性 Bean（绑定 `light-boot.cors`）                        |
| `corsConfigurer`          | `WebMvcConfigurer` | `light-boot.cors.enabled=true` | Spring MVC CORS 配置（`allowedOriginPatterns`）      |
| `corsFilter`              | `CorsFilter`       | `light-boot.cors.enabled=true` | CORS 过滤器；Spring Security 开启 `cors()` 时按此名称复用          |

> `light-boot.cors.enabled` **默认 `false`**，需显式开启；未开启时两个 CORS Bean 均不创建，由业务方自行配置。源通过
`allowedOriginPatterns` 注册（`*` 表示通配所有来源）；当 `allow-credentials=true` 时禁止使用 `*`——两个 CORS Bean
创建前都会执行 `validate()` fail-fast 校验，非法组合直接抛 `IllegalStateException` 终止启动。这是框架强制的安全约束
（通配源无法安全携带凭证），并非 CORS 规范禁止：Spring 的 origin patterns 语义下该组合本会被放行并回显请求来源。

### JacksonConfig — JSON 序列化

`@AutoConfiguration`，注册 Jackson 3 的 `JsonMapperBuilderCustomizer`（`spring-boot-jackson`），统一日期时间格式和时区：

| Java 类型         | JSON 格式               |
|-----------------|-----------------------|
| `LocalDateTime` | `yyyy-MM-dd HH:mm:ss` |
| `LocalDate`     | `yyyy-MM-dd`          |
| `LocalTime`     | `HH:mm:ss`            |

时区：`Asia/Shanghai`。同时注册序列化器和反序列化器（经 `SimpleModule` 覆盖默认格式即可，java time 支持在 Jackson 3 中
已内置于 databind，无需注册独立的 jsr310 模块）。`java.util.Date` 不在本模块定制范围内，走 Jackson 3 默认的 ISO-8601
文本输出。

> Jackson 3 默认行为已符合预期：「反序列化忽略未知属性」（Jackson 2 的 `FAIL_ON_UNKNOWN_PROPERTIES` 关闭）与
> 「日期输出 ISO 文本而非时间戳」（`WRITE_DATES_AS_TIMESTAMPS` 关闭）均无需再显式配置。

---

## Bean 注册清单

所有 Bean 均由 `AutoConfiguration.imports` 注册的 5 个自动配置类以 `@Bean` 方式装配（`WebAutoConfiguration`、`AsyncConfig`、
`CorsConfig`、`JacksonConfig`、`SchedulingConfig`），**无需业务方组件扫描**。`WebProperties` 经
`@EnableConfigurationProperties` 注册。

| Bean                          | 所在配置类            | 类型                        | 条件                                             | 说明                                              |
|-------------------------------|------------------|---------------------------|------------------------------------------------|-------------------------------------------------|
| `httpStatusCodeResolver`      | WebAutoConfiguration | `HttpStatusCodeResolver`  | 始终                                             | `Result.code` → HTTP 状态码（行为受 `light-boot.web.http-status.enabled` 控制），无 `@ConditionalOnMissingBean` |
| `globalExceptionHandler`      | WebAutoConfiguration | `GlobalExceptionHandler`  | `@ConditionalOnMissingBean(GlobalExceptionHandler.class)` | 兜底异常处理器（`@Order()` 默认 `LOWEST_PRECEDENCE`）      |
| `databaseExceptionHandler`    | WebAutoConfiguration | `DatabaseExceptionHandler` | `@ConditionalOnClass(DataAccessException)` + `@ConditionalOnMissingBean` | 数据库异常（`HIGHEST_PRECEDENCE`）                     |
| `saTokenExceptionHandler`     | WebAutoConfiguration | `SaTokenExceptionHandler` | `@ConditionalOnClass(SaTokenException)` + `@ConditionalOnMissingBean` | SaToken 异常（`HIGHEST_PRECEDENCE`）                |
| `taskExecutor`                | AsyncConfig      | `TaskExecutor`（实际 `ThreadPoolTaskExecutor`） | 始终                                             | `@Async` 默认执行器；**无** `@ConditionalOnMissingBean`，不可注册同名 Bean |
| `taskScheduler`               | SchedulingConfig | `ThreadPoolTaskScheduler` | 始终                                             | `@Scheduled` 调度线程池（`configureTasks()` 经 `ObjectProvider` 复用同一实例），无 `@ConditionalOnMissingBean` |
| `corsProperties`              | CorsConfig       | `CorsProperties`          | 始终                                             | 绑定 `light-boot.cors`                            |
| `corsConfigurer`              | CorsConfig       | `WebMvcConfigurer`        | `light-boot.cors.enabled=true`                 | Spring MVC 层 CORS 映射                            |
| `corsFilter`                  | CorsConfig       | `CorsFilter`              | `light-boot.cors.enabled=true`                 | 过滤器链/Security 按 Bean 名复用                        |
| `jsonMapperBuilderCustomizer` | JacksonConfig    | `JsonMapperBuilderCustomizer` | 始终                                             | Jackson 3 日期时间格式与时区，无 `@ConditionalOnMissingBean` |

> 注意：`globalExceptionHandler` / `databaseExceptionHandler` / `saTokenExceptionHandler` 是仅有的三个支持以
> **同类型 Bean 覆盖**的入口（`@ConditionalOnMissingBean` 按 Bean 类型判断，需提供 `GlobalExceptionHandler` 等类型——或其子类——的 Bean）。
> 其余 Bean（`taskExecutor`、`taskScheduler`、`jsonMapperBuilderCustomizer`、`corsProperties` 等）未声明
> `@ConditionalOnMissingBean`，如需换用自己的实现，请使用不同 Bean 名并通过对应入口（`@Async`、`SchedulingConfigurer`、
> `JsonMapperBuilderCustomizer`、`CorsConfigurationSource` 等）接入，避免 Bean 定义冲突。

---

## 依赖

| 依赖                                  | 必选/可选 | 作用                                                                       |
|-------------------------------------|-------|--------------------------------------------------------------------------|
| lightboot-core                 | 必选    | Result / ResultCode / ApiException                                       |
| org.slf4j:slf4j-api                 | 必选    | 日志门面（异常处理器等记录日志）                                       |
| lightboot-logging                   | 可选    | `MdcTaskDecorator`（MDC 传播；未引入时异步退化为仅传播 RequestAttributes）              |
| spring-boot-webmvc / spring-boot-jackson | 可选    | Web MVC 环境与 Jackson 3 定制入口                                             |
| jakarta.servlet-api                 | 可选    | Servlet API（编译期依赖，模块主代码未直接引用，实际由 spring-boot-webmvc 传递提供）                |
| tools.jackson.core:jackson-databind | 可选    | Jackson 3 databind（`JacksonConfig` 定制日期时间格式所需）                       |
| spring-boot-autoconfigure           | 可选    | 自动配置基础                                                                   |
| spring-tx                           | 可选    | DatabaseExceptionHandler 条件加载（`@ConditionalOnClass DataAccessException`） |
| sa-token-core                       | 可选    | SaTokenExceptionHandler 条件加载（`@ConditionalOnClass SaTokenException`）     |
| spring-boot-configuration-processor | 可选    | IDE 配置提示                                                                 |
| lombok                              | 可选    | 简化代码                                                                     |

---

## 配置属性

### light-boot.web.http-status（HTTP 状态码）

| 属性        | 默认值    | 说明                                     |
|-----------|--------|----------------------------------------|
| `enabled` | `true` | 是否让异常响应携带真实 HTTP 状态码；`false` 时全部退回 200 |

### light-boot.async.executor（异步线程池）

| 属性                           | 默认值               | 说明       |
|------------------------------|-------------------|----------|
| `core-pool-size`             | `10`              | 核心线程数    |
| `max-pool-size`              | `20`              | 最大线程数    |
| `queue-capacity`             | `500`             | 队列容量     |
| `keep-alive-seconds`         | `60`              | 空闲线程存活时间 |
| `thread-name-prefix`         | `async-executor-` | 线程名前缀    |
| `allow-core-thread-time-out` | `true`            | 核心线程超时回收 |
| `await-termination-seconds`  | `60`              | 关闭等待时间   |

### light-boot.scheduling.thread-pool（定时任务）

| 属性                                       | 默认值               | 说明        |
|------------------------------------------|-------------------|-----------|
| `pool-size`                              | `10`              | 线程池大小     |
| `thread-name-prefix`                     | `scheduled-task-` | 线程名前缀     |
| `wait-for-tasks-to-complete-on-shutdown` | `true`            | 关闭时等待任务完成 |
| `await-termination-seconds`              | `60`              | 关闭等待时间    |

### light-boot.cors（CORS 跨域）

| 属性                  | 默认值                                       | 说明                                    |
|---------------------|-------------------------------------------|---------------------------------------|
| `enabled`           | `false`                                   | 是否启用 CORS 自动装配（**默认关闭，需显式开启**）        |
| `allowed-origins`   | `[]`（空）                                   | 允许的来源（内部以 `allowedOriginPatterns` 注册） |
| `allowed-methods`   | `["GET","POST","PUT","DELETE","OPTIONS"]` | 允许的方法                                 |
| `allowed-headers`   | `["*"]`                                   | 允许的请求头                                |
| `exposed-headers`   | `["Content-Disposition","Authorization"]` | 暴露的响应头                                |
| `allow-credentials` | `false`                                   | 是否允许凭证                                |
| `max-age`           | `3600`                                    | 预检缓存时间                                |
| `path-pattern`      | `/**`                                     | 匹配路径                                  |

---

## 扩展点

| 扩展点        | 方式                                                             |
|------------|----------------------------------------------------------------|
| 自定义异常处理    | 注册更高优先级的 `@RestControllerAdvice`（`@Order(HIGHEST_PRECEDENCE)`） |
| 自定义线程池     | 定义自定义 `Executor`/`ThreadPoolTaskExecutor` Bean，用 `@Async("beanName")` 指定，或自行实现 `AsyncConfigurer`（框架 `taskExecutor` 无 `@ConditionalOnMissingBean`，注册同名 Bean 会因 Bean 定义冲突启动失败） |
| 自定义 CORS   | 注册 `CorsConfigurationSource` Bean                              |
| 覆盖 JSON 格式 | 字段上使用 `@JsonFormat` 注解                                         |

---

## 注意事项

- 异常响应默认携带真实 HTTP 状态码（由 `HttpStatusCodeResolver` 按 `Result.code` 显式映射），
  `light-boot.web.http-status.enabled=false` 时退回全 200；业务结果始终由 `Result.code` 区分
- `BindException` 同时处理 `MethodArgumentNotValidException`（@RequestBody 校验）和 `BindException`（表单绑定校验）
- `@Async` 和 `@Scheduled` 的内部方法调用不走代理，不生效
- `AsyncConfig` 组合 decorator 同时传播 `RequestAttributes` 和 MDC（含 TraceId）：RequestAttributes 由
  `wrapWithContext()` 负责，MDC 由 logging 模块 `MdcTaskDecorator` 负责；logging 未装配时退化为仅传播 RequestAttributes
- `light-boot.cors.enabled` 默认 `false`，需显式开启；开启后 `allow-credentials: true` 时 `allowed-origins` 不能为 `*`
  （启动时 `validate()` fail-fast 抛 `IllegalStateException`，属框架安全约束而非 CORS 规范禁止）
- 定时任务配置前缀是 `light-boot.scheduling.thread-pool`（不是 `light-boot.scheduling`）
