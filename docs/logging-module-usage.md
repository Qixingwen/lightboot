# logging — 日志模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速验证](#快速验证)
- [功能详解](#功能详解)
    - [TraceId 全链路追踪](#traceid-全链路追踪)
    - [日志脱敏](#日志脱敏)
    - [HTTP 请求日志](#http-请求日志)
    - [定时任务 TraceId](#定时任务-traceid)
- [配置 Logback](#配置-logback)
- [异步线程 MDC 传播](#异步线程-mdc-传播)
- [配置属性一览](#配置属性一览)
- [自定义扩展](#自定义扩展)
    - [自定义脱敏规则](#自定义脱敏规则)
    - [选择性启用脱敏](#选择性启用脱敏)
    - [自定义 TraceId 生成策略](#自定义-traceid-生成策略)
    - [自定义请求日志格式](#自定义请求日志格式)
- [兼容性说明](#兼容性说明)
- [常见问题](#常见问题)

---

## 模块简介

`lightboot-logging` 提供四个核心能力，引入依赖即自动生效（HTTP 耗时指标需引入 actuator）：

| 能力            | 说明                          | 默认状态 |
|---------------|-----------------------------|------|
| TraceId 全链路追踪 | 每个请求自动分配唯一 TraceId，贯穿日志和响应头 | 开启   |
| 日志脱敏          | 自动脱敏手机号、身份证号、银行卡号、邮箱、凭证    | 开启   |
| HTTP 请求日志     | 记录每个请求的方法、URI、客户端 IP、状态码和耗时 | 开启   |
| HTTP 耗时指标     | 每个请求自动记录耗时 Timer 指标 `light-boot.http.requests`（tag: `method`/`status`） | 需 actuator |

**设计原则：零侵入** — 业务代码无需任何修改，所有功能通过配置开关控制。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-logging</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

引入后自动生效，无需 `@Import` 或 `@ComponentScan`。

---

## 快速验证

### 1. 配置 Logback（必须）

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

### 2. 启动应用，发送请求

```bash
curl http://localhost:8080/api/user/login -H "Content-Type: application/json" -d '{"phone":"13812348888"}'
```

### 3. 查看日志输出

```text
2026-05-12 14:30:00.123 [http-nio-8080-exec-1] [a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6] INFO  c.e.demo.UserController - 用户手机号 138****8888 登录成功
2026-05-12 14:30:00.156 [http-nio-8080-exec-1] [a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6] INFO  c.n.l.l.r.RequestLogFilter - HTTP POST /api/user/login from 192.168.1.100 -> 200 (33ms)
```

- `[a1b2c3d4...]` — 自动生成的 TraceId
- `138****8888` — 手机号自动脱敏
- `from 192.168.1.100` — 客户端 IP（默认取 TCP 直连地址；仅当配置了 `light-boot.logging.request.trusted-proxies` 时才解析
  `X-Forwarded-For` 等转发头）
- `-> 200 (33ms)` — 响应状态和耗时

### 4. 检查响应头

```bash
curl -I http://localhost:8080/api/user/login
# 响应头包含：X-Trace-Id: a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
```

---

## 功能详解

### TraceId 全链路追踪

**工作原理：**

1. 每个 HTTP 请求进入时，`TraceFilter` 自动从请求头 `X-Trace-Id` 读取上游 TraceId
2. 如果不存在或格式非法，生成新的 32 位 TraceId（UUID 去连字符）。上游传入的 TraceId 必须为
   **16-64 位小写十六进制**（`a-f0-9`）；含大写字母、过长、过短或含特殊字符（如 CRLF）都会被判为非法并重新生成
3. 写入 MDC（`%X{traceId}` 可直接输出）和响应头（前端/下游可获取）
4. 请求结束后自动清理，防止线程池复用时泄漏

**在业务代码中获取 TraceId：**

```java
import cn.nextdev.lightboot.logging.trace.TraceContext;

String traceId = TraceContext.get();
// 例：传递给下游微服务调用
httpClient.setHeader("X-Trace-Id", traceId);
```

**微服务透传：**

上游服务从响应头取回 `X-Trace-Id` 后，需将其写入对下游调用的请求头；下游 TraceFilter 自动读取请求头并延续同一个 TraceId（框架不会自动读取上游的响应头），形成完整调用链路。

```text
客户端 → 网关 (生成 TraceId) → 服务A (继承 TraceId) → 服务B (继承 TraceId)
```

### 日志脱敏

**内置脱敏规则：**

| 规则                   | 脱敏效果                                    | 示例                                                           |
|----------------------|-----------------------------------------|--------------------------------------------------------------|
| 手机号                  | 保留前 3 位和后 4 位                           | `13812348888` → `138****8888`                                |
| 身份证号（18位）            | 保留前 3 位和后 4 位                           | `110101199001011234` → `110***********1234`                  |
| 银行卡号                 | 保留前 4 位和后 4 位                           | `6222021234561234` → `6222********1234`                      |
| 邮箱                   | 保留首字母和域名                                | `test@example.com` → `t***@example.com`                      |
| 凭证（Bearer/Basic/JWT） | 凭证键 + scheme + 值整体 → `******`      | `Authorization: Bearer xxx` → `Authorization: ******`        |
| 凭证（JSON）             | JSON 凭证键值对 → `"******"`                 | `"password":"xxx"` → `"password":"******"`                   |
| 凭证（通用）               | 凭证键后的任意值 → `******`                     | `password: xxx` → `password: ******`                         |

> 凭证键集合：`password`、`passwd`、`secret`、`token`、`authorization`、`api_key`/`apiKey`/`api-key`。规则按枚举序应用，旧的中文姓名
`NAME` 规则已移除（误伤率高）。注意通用 `CREDENTIAL` 规则的值为**贪婪** `\S+`：一直吃到下一个空白字符为止，引号、逗号、
分号等分隔符会一并被打码（宁可多脱敏、不漏脱敏）。

**默认脱敏范围：** 只有 HTTP 请求日志的查询参数会被 `RequestLogFilter` 自动脱敏。应用日志消息和异常栈需要你在
`logback-spring.xml` 中注册并使用 `%maskMsg`（消息）/ `%maskEx`（异常栈）后才会脱敏；未配置时应用就绪会输出一次 WARN 提示。

**完成接线后消音：** 确认已注册 `conversionRule` 并在 pattern 中使用了 `%maskMsg`/`%maskEx` 后，置
`light-boot.logging.mask.wired=true` 即可抑制该启动 WARN：

```yaml
light-boot:
  logging:
    mask:
      wired: true   # 声明脱敏转换器已正确接线，不再告警
```

> 框架不检测 Logback 运行时 layout，`wired` 仅是使用方的显式声明——未实际接线却置 `true` 会掩盖「应用日志未脱敏」的事实。也可注册同名
> Bean 覆盖 `MaskingStartupLogger` 消音。

**使用方式：** 在 `logback-spring.xml` 中使用 `%maskMsg` 替代 `%msg`、`%maskEx` 替代 `%ex`，日志自动脱敏，业务代码无需修改。

### HTTP 请求日志

**自动记录每个请求：**

```text
HTTP {方法} {URI}?{查询参数} from {客户端IP} -> {状态码} ({耗时}ms)
```

- 查询参数处理顺序：CRLF/制表符清洗 → 脱敏 → 截断（超过 200 字符截断并标记 `...(truncated)`）
- 耗时使用 `System.nanoTime()` 精确计算
- **慢请求标记：** `light-boot.logging.request.slow-threshold-ms`（默认 1000ms），请求耗时 `>=` 阈值时日志级别提升为 WARN 并追加
  `[SLOW]`；设为 0 或负值禁用
- 客户端 IP 解析：仅当 `remoteAddr` 命中 `light-boot.logging.request.trusted-proxies`（CIDR 列表）时才解析 `X-Forwarded-For` /
  `X-Real-IP` 等转发头；默认空列表表示不信任任何代理，直接使用 TCP 连接地址，防止客户端伪造

**排除路径：** 默认排除 `/actuator/**` 和 `/favicon.ico`，可自定义。

### 定时任务 TraceId

`@Scheduled` 定时任务自动注入 TraceId（32 位纯 hex，与 HTTP 请求格式一致，便于向下游透传），并以独立 MDC key
`traceSource=scheduled` 标记来源（可在 pattern 中加 `%X{traceSource:-}` 输出，不加不影响功能），无需手动处理。

```java
@Component
public class DataSyncTask {

    @Scheduled(cron = "0 0 2 * * ?")
    public void syncData() {
        // 日志中自动出现 32 位纯 hex 的 TraceId（如 [f7e8d9c0...]，无 sched- 前缀）
        log.info("数据同步开始");
    }
}
```

> 此功能需要 AOP 依赖（Spring Boot 4 中为 `spring-boot-starter-aspectj`）。如项目未引入 AOP，切面不加载，定时任务的 TraceId 为空（不影响其他功能）。

---

## 配置 Logback

### 关键配置项

| 配置项                         | 说明                          | 必须配置               |
|-----------------------------|-----------------------------|--------------------|
| `conversionRule`（`maskMsg`） | 注册消息脱敏 Converter            | 是（使用 `%maskMsg` 时） |
| `conversionRule`（`maskEx`）  | 注册异常栈脱敏 Converter           | 是（使用 `%maskEx` 时）  |
| `%X{traceId:-}`             | 输出 TraceId，`:-` 表示无值时输出空字符串 | 是（需要 TraceId 时）    |
| `%maskMsg`                  | 替代 `%msg`，输出脱敏后的日志消息        | 是（需要脱敏消息时）         |
| `%maskEx`                   | 替代 `%ex`，输出脱敏后的异常栈          | 按需（需要脱敏异常栈时）       |

### 完整配置示例（含文件滚动）

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

> 如果不需要脱敏，可使用 `%msg` 替代 `%maskMsg`，其余配置不变。

---

## 异步线程 MDC 传播

`@Async` 方法在新线程执行，MDC 默认不会传播。需配合 web 模块 `AsyncConfig` 使用。

**使用 `lightboot-web` 的项目：** web 模块的 `AsyncConfig` 会自动传播 MDC（含 TraceId），无需额外配置。

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

**自定义线程池：** 可直接注入 logging 模块提供的 `mdcTaskDecorator` Bean（`cn.nextdev.lightboot.logging.trace.MdcTaskDecorator`）。
它会把提交线程的 MDC（含 TraceId）复制到子线程，并在子线程执行后**恢复其原有 MDC**（而非直接清空，线程池复用安全），
例如 `executor.setTaskDecorator(mdcTaskDecorator::decorate)`。

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
                } else {
                    MDC.clear(); // 提交线程无 MDC 时清除池内线程可能残留的陈旧上下文（与 MdcTaskDecorator 语义一致）
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

---

## 配置属性一览

所有配置统一使用 `light-boot.logging` 前缀：

```yaml
light-boot:
  logging:
    enabled: true                      # 总开关，关闭后整个模块（含 HTTP 指标采集）不加载
    trace:
      enabled: true                    # TraceId 开关
      header-name: X-Trace-Id          # 上下游透传的请求头名称
    request:
      enabled: true                    # 请求日志开关
      slow-threshold-ms: 1000          # 慢请求阈值（ms），>= 阈值记 WARN + [SLOW]；0/负值禁用
      exclude-paths:                   # 排除路径（Ant 风格），匹配的路径不记录日志
        - /actuator/**
        - /favicon.ico
      trusted-proxies: []              # 受信代理 CIDR，仅这些来源的转发头才被采信（默认空=不信任任何代理）
    mask:
      enabled: true                    # 脱敏开关
      wired: false                     # 已完成 %maskMsg/%maskEx 接线时置 true，抑制启动 WARN（框架不检测运行时 layout）
```

| 属性                                       | 类型             | 默认值                                | 说明                                                                                              |
|------------------------------------------|----------------|------------------------------------|-------------------------------------------------------------------------------------------------|
| `light-boot.logging.enabled`                   | `Boolean`      | `true`                             | 模块总开关                                                                                           |
| `light-boot.logging.trace.enabled`             | `Boolean`      | `true`                             | TraceId 过滤器开关                                                                                   |
| `light-boot.logging.trace.header-name`         | `String`       | `X-Trace-Id`                       | TraceId 请求头名称                                                                                   |
| `light-boot.logging.request.enabled`           | `Boolean`      | `true`                             | 请求日志开关                                                                                          |
| `light-boot.logging.request.slow-threshold-ms` | `long`         | `1000`                             | 慢请求阈值（ms），耗时 `>=` 阈值记 WARN + `[SLOW]`；0/负值禁用                                                    |
| `light-boot.logging.request.exclude-paths`     | `List<String>` | `["/actuator/**", "/favicon.ico"]` | 排除路径（Ant 风格）                                                                                    |
| `light-boot.logging.request.trusted-proxies`   | `List<String>` | `[]`                               | 受信代理 CIDR 列表；仅命中时才解析转发头，空=不信任任何代理                                                               |
| `light-boot.logging.mask.enabled`              | `Boolean`      | `true`                             | 日志脱敏开关                                                                                          |
| `light-boot.logging.mask.wired`                | `Boolean`      | `false`                            | 是否已在 `logback-spring.xml` 完成 `%maskMsg`/`%maskEx` 接线；置 `true` 抑制 `MaskingStartupLogger` 启动 WARN |

---

## 自定义扩展

### 自定义脱敏规则

实现 `LogMasker` 接口并注册为 Spring Bean，自动替代默认实现：

```java
@Configuration
public class MyLoggingConfig {

    @Bean
    public LogMasker logMasker() {
        return text -> {
            if (text == null) {
                return null;
            }
            // 脱敏订单号中间 6 位
            return text.replaceAll("ORD(\\d{4})\\d{6}(\\d{4})", "ORD$1******$2");
        };
    }
}
```

### 选择性启用脱敏

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

**内置规则枚举（`MaskPattern`）：**

| 枚举值                          | 说明              |
|-----------------------------|-----------------|
| `MaskPattern.PHONE`         | 手机号             |
| `MaskPattern.ID_CARD`       | 身份证号            |
| `MaskPattern.BANK_CARD`     | 银行卡号            |
| `MaskPattern.EMAIL`         | 邮箱              |
| `MaskPattern.CREDENTIAL_BEARER` | Bearer/Basic/JWT 令牌 |
| `MaskPattern.CREDENTIAL_JSON`   | JSON 形态凭证键值对    |
| `MaskPattern.CREDENTIAL`        | 通用凭证键值对         |

### 自定义 TraceId 生成策略

生成入口是静态的 `TraceContext.generate()`（默认 32 位 UUID 去连字符），`TraceFilter` 内部没有可覆盖的生成方法。自定义策略即自定义一个 Filter、把对该方法的调用换成自己的生成逻辑，并通过 `light-boot.logging.trace.enabled=false` 关闭内置 `TraceFilter`：

```java
@Bean
public Filter traceFilter(LoggingProperties properties) {
    String headerName = properties.getTrace().getHeaderName();
    return new OncePerRequestFilter() {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            try {
                // 与 TraceFilter.doFilterInternal 相同：读取请求头并校验，非法则走自定义生成
                String header = request.getHeader(headerName);
                String traceId = header != null && header.matches("^[a-f0-9]{16,64}$") ? header : MyIdGenerator.next();
                TraceContext.set(traceId);
                response.setHeader(headerName, traceId);
                chain.doFilter(request, response);
            } finally {
                TraceContext.clear();
            }
        }
    };
}
```

### 自定义请求日志格式

覆盖 `RequestLogFilter` Bean，添加 User-Agent 等自定义字段：

```java
@Bean
public RequestLogFilter requestLogFilter(LoggingProperties properties,
                                         org.springframework.beans.factory.ObjectProvider<LogMasker> maskerProvider) {
    return new RequestLogFilter(
            properties.getRequest().getExcludePaths(),
            properties.getRequest().getSlowThresholdMs(),
            maskerProvider.getIfAvailable(),   // mask.enabled=false 时不存在 LogMasker Bean，此处为 null（查询串原样记录）
            properties.getRequest().getTrustedProxies()) {
        // 自定义实现
    };
}
```

> 注册同类型 Bean 后，框架的 `@ConditionalOnMissingBean` 会自动让路，无需额外配置。

---

## 兼容性说明

| 环境                       | 兼容性  | 说明                                                       |
|--------------------------|------|----------------------------------------------------------|
| Spring MVC（Servlet）      | 支持   | TraceFilter 和 RequestLogFilter 基于 `OncePerRequestFilter` |
| Spring WebFlux（Reactive） | 不支持  | 需使用 `WebFilter` + Reactor Context，暂不支持                   |
| 非 Web 应用                 | 部分支持 | TraceFilter 和 RequestLogFilter 不加载，脱敏仍可用                 |
| Spring Boot 4.x          | 支持   | 使用 `jakarta.servlet`                                     |
| Spring Boot 2.x          | 不兼容  | 使用 `javax.servlet`，自动配置机制不同                              |

---

## 常见问题

### 日志中没有 TraceId

检查 `logback-spring.xml` 的 pattern 中是否包含 `%X{traceId:-}`。`:-` 表示无值时输出空字符串，避免输出 `null`。

### 脱敏没有生效

1. 确认 `logback-spring.xml` 中已注册 `conversionRule` 并使用 `%maskMsg`
2. 确认 `light-boot.logging.mask.enabled` 未设置为 `false`
3. 注意 `light-boot.logging.mask.wired=true` **只是声明**，框架不会检测运行时 layout 是否真的接了 `%maskMsg`/`%maskEx`——它只抑制启动
   WARN，不影响脱敏是否生效

### 异步方法中 TraceId 丢失

确保使用 `lightboot-web` 提供的 `taskExecutor`（内置 MDC 传播）。如果使用自定义线程池或 `CompletableFuture`，需手动包装
MDC 传播（参见 [异步线程 MDC 传播](#异步线程-mdc-传播)）。

### 定时任务没有 TraceId

确保项目中引入了 AOP 依赖（Spring Boot 4 中为 `spring-boot-starter-aspectj`）。定时任务的 TraceId 由 AOP 切面注入，无 AOP 依赖时切面不加载。

### 不想记录某些路径的请求日志

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

### 关闭整个日志模块

```yaml
light-boot:
  logging:
    enabled: false
```

### 只关闭脱敏，保留 TraceId 和请求日志

```yaml
light-boot:
  logging:
    mask:
      enabled: false
```

同时将 `logback-spring.xml` 中的 `%maskMsg` 改回 `%msg`。
