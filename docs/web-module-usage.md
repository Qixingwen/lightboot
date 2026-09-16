# web — Web 模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速验证](#快速验证)
- [功能详解](#功能详解)
    - [全局异常处理](#全局异常处理)
    - [CORS 跨域配置](#cors-跨域配置)
    - [JSON 序列化配置](#json-序列化配置)
    - [异步线程池](#异步线程池)
    - [定时任务线程池](#定时任务线程池)
    - [SaToken 异常处理](#satoken-异常处理)
- [配置属性一览](#配置属性一览)
- [自定义扩展](#自定义扩展)
- [常见问题](#常见问题)

---

## 模块简介

`lightboot-web` 提供 Web 层基础设施能力：

| 能力           | 说明                      | 默认状态 |
|--------------|-------------------------|------|
| 全局异常处理       | 统一拦截异常，返回标准 `Result` 响应 | 开启   |
| CORS 跨域      | 可配置的跨域策略                | 关闭（需显式开启） |
| JSON 序列化     | 统一日期格式和时区               | 开启   |
| 异步线程池        | 生产级 `@Async` 线程池        | 开启   |
| 定时任务线程池      | 多线程 `@Scheduled` 调度器    | 开启   |
| SaToken 异常处理 | 认证授权异常统一处理              | 条件加载 |

**设计原则：引入即生效** — 所有功能提供合理的默认值，无需额外配置。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-web</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

引入后自动生效，无需 `@Import` 或 `@ComponentScan`。

---

## 快速验证

### 1. 创建 Controller 与 Service

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }
}

@Service
public class UserService {

    public UserVO getById(Long id) {
        UserPO user = userRepository.findById(id);
        // 用户不存在时抛出 ApiException（cn.nextdev.lightboot.core.exception.Asserts），
        // 交由全局异常处理器统一转换为 Result 响应
        Asserts.notNull(user, "用户不存在");
        return UserConverter.toVO(user);
    }
}
```

### 2. 访问不存在的用户

```bash
curl http://localhost:8080/api/users/999
```

### 3. 查看响应

```json
{
    "code": -1,
    "message": "用户不存在",
    "data": null
}
```

> Service 抛出的 `ApiException`（`Asserts.fail` 系列方法内部抛出）被 `GlobalExceptionHandler` 自动拦截，
> 转换为统一的 `Result` 响应；Controller 无需 try-catch。
>
> **HTTP 状态码：** 异常响应默认携带与 `Result.code` 语义对应的真实 HTTP 状态码（由 `HttpStatusCodeResolver`
> 显式映射：`-1`→500、`40000`→400、`40400`→404、`50000`→500 等）。响应体仍是统一的 `Result`，业务结果由 `Result.code` 区分。如需退回全
> 200，设 `light-boot.web.http-status.enabled=false`。
>
> **`traceId` 字段：** 上述示例为仅引入 `lightboot-web` 时的响应。若同时引入 `lightboot-logging`，`Result` 会自动携带从 MDC 读取的
> `traceId`（无 traceId 时因 `NON_NULL` 序列化策略省略该字段），响应 JSON 会多出此字段。

---

## 功能详解

### 全局异常处理

#### 业务异常

Service 层抛出 `ApiException`，Controller 无需 try-catch：

```java
@Service
public class UserService {

    public UserVO getById(Long id) {
        User user = userRepository.findById(id);
        Asserts.notNull(user, "用户不存在");
        return UserConverter.toVO(user);
    }
}
```

响应：

```json
{
    "code": -1,
    "message": "用户不存在",
    "data": null
}
```

#### 参数校验异常

使用 `@Valid` 注解校验请求参数：

```java
@PostMapping
public Result<Void> createUser(@RequestBody @Valid UserCreateDTO dto) {
    userService.create(dto);
    return Result.success();
}

@Getter
@Setter
public class UserCreateDTO {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @Email(message = "邮箱格式不正确")
    private String email;

    @Min(value = 18, message = "年龄不能小于 18")
    private Integer age;
}
```

校验失败响应：

```json
{
    "code": 40000,
    "message": "username: 用户名不能为空; email: 邮箱格式不正确",
    "data": null
}
```

#### 其他自动处理的异常

| 触发方式       | 响应 code | 响应 message                     |
|------------|---------|----------------------------|
| 请求参数缺失     | 40000   | 缺少必需参数: {参数名}              |
| 缺少必需请求头    | 40000   | 缺少必需请求头: {请求头名}            |
| 参数类型不匹配    | 40000   | 参数类型错误: '{参数名}' 应为 {类型} 类型 |
| 请求体解析失败    | 40000   | 请求体格式错误或数据类型不匹配            |
| HTTP 方法不支持 | 40000   | 不支持的请求方法: {方法}，支持的方法: {列表} |
| 媒体类型不支持    | 40000   | 不支持的媒体类型: {Content-Type}   |
| 非法参数（IllegalArgumentException） | 40000 | 请求参数不合法              |
| 文件大小超限     | 41300   | 文件大小超过限制（HTTP 413）         |
| 空指针异常      | 50000   | 系统内部错误                     |

> 参数校验失败（`@Valid` / 表单绑定）同样返回 40000，message 为「字段名: 校验消息」以 `; ` 拼接。完整映射表见
> `web-module-design.md` 的「GlobalExceptionHandler 异常映射」。

> 业务异常（`ApiException`）按其错误码映射 HTTP 状态码；限流类异常在 `getRetryAfterSeconds() > 0` 时会额外写入 `Retry-After` 响应头。

### CORS 跨域配置

#### 默认关闭，需显式开启

`light-boot.cors.enabled` 默认为 `false`，未显式开启时框架不装配任何 CORS Bean，由业务方自行配置。开启后 `allowed-origins`
默认为**空列表（不放行任何来源，必须显式配置）**，HTTP 方法、请求头、暴露头有默认值（源通过 `allowedOriginPatterns` 注册）：

```yaml
# 开启 CORS（enabled 默认 false，必须显式设为 true）
light-boot:
  cors:
    enabled: true
    allowed-origins: ["*"]
    allowed-methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
    allowed-headers: ["*"]
    exposed-headers: ["Content-Disposition", "Authorization"]
    allow-credentials: false
    max-age: 3600
```

#### 生产环境配置

```yaml
light-boot:
  cors:
    enabled: true                       # 必须显式设为 true（默认 false）
    allowed-origins:
      - "https://www.example.com"
      - "https://admin.example.com"
    allow-credentials: true
    max-age: 7200
```

> **注意：** `allow-credentials: true` 时，`allowed-origins` 不能为 `*`，必须指定具体域名；否则启动时 fail-fast 校验
> 直接抛 `IllegalStateException` 终止应用（框架安全约束，避免通配源携带凭证）。

### JSON 序列化配置

所有日期类型自动格式化，无需 `@JsonFormat` 注解：

| Java 类型         | JSON 输出                 |
|-----------------|-------------------------|
| `LocalDateTime` | `"2026-06-11 14:30:00"` |
| `LocalDate`     | `"2026-06-11"`          |
| `LocalTime`     | `"14:30:00"`            |
| `Date`          | Jackson 3 默认 ISO-8601 文本（本模块未定制，非 `yyyy-MM-dd HH:mm:ss`） |

时区统一为 `Asia/Shanghai`。java time 支持在 Jackson 3 中已内置于 databind，无需额外注册 jsr310 模块。

**覆盖全局配置（特定字段）：**

```java
@JsonFormat(pattern = "yyyy/MM/dd", timezone = "UTC")
private LocalDate specialDate;
```

### 异步线程池

#### 使用 @Async

```java
@Service
public class EmailService {

    @Async("taskExecutor")  // 指定使用框架配置的线程池
    public void sendWelcomeEmail(String email) {
        // 异步执行，不阻塞主线程
        emailClient.send(email, "欢迎注册");
    }
}
```

#### 自定义线程池参数

```yaml
light-boot:
  async:
    executor:
      core-pool-size: 10
      max-pool-size: 50
      queue-capacity: 1000
      keep-alive-seconds: 120
      thread-name-prefix: "async-"
```

#### 线程池工作原理

```text
任务提交 → 核心线程 (corePoolSize=10)
              │ 满了
              ▼
         队列 (queueCapacity=500)
              │ 满了
              ▼
         扩展线程 (到 maxPoolSize=20)
              │ 满了
              ▼
         拒绝策略 (记录 WARN 日志，在调用者线程同步执行任务 — Caller-Runs)
```

> 拒绝策略为 **Caller-Runs**：池满时任务退回提交者线程同步执行，不会丢失任务、也不会抛出异常，相当于对上游形成天然限流。

> **注意：** 默认 `allow-core-thread-time-out=true`，核心线程空闲超过 `keep-alive-seconds`（默认 60s）后同样会被回收，
> 并非始终保活；需要常驻核心线程可将其设为 `false`（见下方「配置属性一览」）。

### 定时任务线程池

#### 使用 @Scheduled

```java
@Component
@Slf4j
public class DataSyncTask {

    @Scheduled(cron = "0 0 2 * * ?")
    public void syncData() {
        log.info("数据同步开始");
        // 多个定时任务可并行执行（线程池大小默认 10）
    }
}
```

#### 自定义调度参数

```yaml
light-boot:
  scheduling:
    thread-pool:
      pool-size: 10
      thread-name-prefix: "scheduled-"
```

#### 错误处理

定时任务抛出异常时，调度器会记录日志但不中断，后续调度继续执行（一般异常与线程池拒绝为 ERROR，`InterruptedException` 为 WARN）：

```text
ERROR c.n.l.w.c.SchedulingConfig - Unexpected error in scheduled task ...
```

### SaToken 异常处理

#### 条件加载

仅当 `sa-token-core` 在 classpath 上时加载。引入 SaToken 依赖即可自动启用（本项目面向 Spring Boot 4，使用
`sa-token-spring-boot4-starter`，Sa-Token 自 1.45.0 起提供）：

```xml
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot4-starter</artifactId>
    <version>1.46.0</version>
</dependency>
```

> **版本需自行管理**：lightboot-bom 仅管理 `sa-token-core`（1.46.0），不管理任何 SaToken starter，
> 因此 `<version>` 必须显式声明（或由使用方自己的 dependencyManagement 提供），否则依赖无法解析。

#### 自动处理的 SaToken 异常

| 异常                                            | 响应 code | 响应 message                                 |
|-----------------------------------------------|---------|----------------------------------------|
| `NotLoginException`                           | 40100   | 登录会话失效，请重新登录（`Result.unauthorized()`）  |
| `NotPermissionException` / `NotRoleException` | 40300   | 没有相关权限（`Result.forbidden()`）           |
| `SaTokenContextException`                     | -1      | 认证上下文异常，请检查系统配置                        |
| `SaTokenException`（兜底）                        | 40100   | 其他 SaToken 异常（`Result.unauthorized()`） |

---

## 配置属性一览

```yaml
light-boot:
  # HTTP 状态码
  web:
    http-status:
      enabled: true                   # 是否让异常响应携带真实 HTTP 状态码（默认 true）

  # 异步线程池配置
  async:
    executor:
      core-pool-size: 10                 # 核心线程数
      max-pool-size: 20                  # 最大线程数
      queue-capacity: 500                # 队列容量
      keep-alive-seconds: 60             # 空闲线程存活时间
      thread-name-prefix: "async-executor-"  # 线程名前缀
      allow-core-thread-time-out: true   # 核心线程超时回收
      await-termination-seconds: 60      # 关闭等待时间

  # 定时任务配置
  scheduling:
    thread-pool:
      pool-size: 10                        # 线程池大小
      thread-name-prefix: "scheduled-task-" # 线程名前缀
      wait-for-tasks-to-complete-on-shutdown: true
      await-termination-seconds: 60

  # CORS 跨域配置（enabled 默认 false，需显式开启）
  cors:
    enabled: true                       # 必须显式设为 true 才装配 CORS
    allowed-origins: ["*"]              # 默认 []（空），内部以 allowedOriginPatterns 注册
    allowed-methods: ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
    allowed-headers: ["*"]
    exposed-headers: ["Content-Disposition", "Authorization"]
    allow-credentials: false
    max-age: 3600
    path-pattern: "/**"
```

---

## 自定义扩展

### 覆盖全局异常处理

注册自定义的 `@RestControllerAdvice` Bean：

```java
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MyExceptionHandler {

    @ExceptionHandler(MyBusinessException.class)
    public Result<Void> handle(MyBusinessException e) {
        return Result.failed(e.getErrorCode());
    }
}
```

### 覆盖 CORS 配置

注册自定义的 `CorsConfigurationSource` Bean（Spring Security 场景）。注意框架自动装配的过滤器 Bean
名为 `corsFilter`（类型 `CorsFilter`，受 `light-boot.cors.enabled` 控制）——自定义时建议关闭框架的
CORS 装配，避免两条链路重复处理：

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://www.example.com"));
    config.setAllowedMethods(List.of("GET", "POST"));
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

### 使用自定义线程池

```java
@Bean("myExecutor")
public ThreadPoolTaskExecutor myExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("my-executor-");
    return executor;
}

// 使用
@Async("myExecutor")
public void myAsyncTask() { ... }
```

---

## 常见问题

### 异常处理不生效？

1. 框架的三个异常处理器由自动配置（`WebAutoConfiguration` 的 `@Bean`）装配，**无需组件扫描**；只有你自定义的
   `@RestControllerAdvice` 才需要确认其所在包被组件扫描覆盖
2. 确保异常是在 Controller 层抛出的（Filter 中的异常不会被 `@RestControllerAdvice` 拦截）
3. 检查是否有更高优先级的 `@RestControllerAdvice` 拦截了异常

### @Async 不生效？

1. 确保 `@Async` 方法所在的类是 Spring 管理的 Bean
2. 确保是外部调用（内部方法调用不走 AOP 代理）
3. 确保 `@EnableAsync` 已启用（web 模块自动配置）

### 日期格式不生效？

1. 确保使用的是 `LocalDateTime` / `LocalDate` / `LocalTime`（推荐）；`Date` 不在本模块定制范围，走 Jackson 3 默认 ISO 输出
2. 如果在字段上使用了 `@JsonFormat`，它会覆盖全局配置
3. Jackson 3 的 java time 支持已内置于 databind（无需 `jackson-datatype-jsr310`，Spring Boot Web 默认包含）

### CORS 配置不生效？

1. 如果同时使用了 Spring Security，需要在 Security 配置中启用 CORS（Spring Security 7 语法）：

   ```java
   http.cors(Customizer.withDefaults())
       .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
   ```

   Security 的 `cors()` 会按 Bean 名 `corsFilter` 复用框架注册的 `CorsFilter`。注意框架的 `corsFilter` 与 MVC 层
   `corsConfigurer` 同受 `light-boot.cors.enabled` 控制，同时生效时同一请求会经过过滤器链与 MVC 映射两条 CORS
   处理路径——集成 Security 时建议按链路只保留其一。

2. 检查 `allowed-origins` 和 `allow-credentials` 的组合是否合法（`credentials=true` 时不能为 `*`；非法组合在启动时
   fail-fast 抛 `IllegalStateException`）

### 数据库异常没有被 DatabaseExceptionHandler 处理？

确保引入了 `spring-tx` 依赖。`DatabaseExceptionHandler` 通过
`@ConditionalOnClass("org.springframework.dao.DataAccessException")` 条件加载，没有事务依赖时不注册。

### 如何完全替换 / 覆盖全局异常处理？

两种方式机制不同，按需选择：

**方式一：完全替换框架处理器** —— 注册一个 `GlobalExceptionHandler` 类型（或其子类）的 Bean。
`WebAutoConfiguration` 的 `globalExceptionHandler` @Bean 方法上的 `@ConditionalOnMissingBean(GlobalExceptionHandler.class)` 按 **Bean 类型**判断，
检测到同类型 Bean 后，框架兜底处理器不再装配：

```java
@Bean
public GlobalExceptionHandler myGlobalExceptionHandler(HttpStatusCodeResolver resolver) {
    return new GlobalExceptionHandler(resolver);   // 或返回自定义子类
}
```

**方式二：并存覆盖（推荐）** —— 注册任意自定义 `@RestControllerAdvice` 并使用更高的 `@Order` 优先级（见上文
[自定义扩展](#自定义扩展)）。注意此时框架处理器**仍然存在**——注册自定义 advice 并不会触发 `@ConditionalOnMissingBean`
让路，它只对你声明了匹配 `@ExceptionHandler` 的异常让位，其余异常仍由框架兜底处理（通常这正是期望行为）。

### 定时任务异常后不再执行？

不会。`SchedulingConfig` 配置了自定义错误处理器，异常仅记录日志，不会中断调度器。下一个调度周期会继续执行。
