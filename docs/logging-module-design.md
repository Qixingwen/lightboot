# logging 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+

---

## 定位

框架的可观测性基础设施模块，提供 **TraceId 全链路追踪**、**日志脱敏**、**HTTP 请求日志** 和 **HTTP 耗时指标**（需 actuator）四大能力。引入依赖即生效，业务代码零侵入。

---

## 模块结构

```text
cn.nextdev.lightboot.logging/
├── config/
│   ├── LoggingAutoConfiguration.java        — 自动配置入口
│   ├── LoggingObservabilityAutoConfiguration.java — 指标采集（需 actuator）
│   └── LoggingProperties.java              — 配置属性（light-boot.logging.*）
├── trace/
│   ├── TraceContext.java                   — TraceId 上下文（ThreadLocal + MDC）
│   ├── TraceFilter.java                    — Servlet Filter：注入/校验/清理 TraceId
│   ├── ScheduledTraceAspect.java           — @Scheduled 定时任务 TraceId 注入
│   └── MdcTaskDecorator.java               — 异步线程 MDC 传播装饰器
├── mask/
│   ├── LogMasker.java                      — 脱敏接口
│   ├── DefaultLogMasker.java               — 默认正则脱敏实现
│   ├── MaskPattern.java                    — 内置脱敏规则枚举
│   ├── MaskingConverter.java               — Logback %maskMsg 自定义 Converter
│   ├── MaskingThrowableConverter.java      — Logback %maskEx 异常栈脱敏 Converter
│   ├── MaskingStartupLogger.java           — 未配置脱敏时的启动提醒
│   ├── MaskConstants.java                  — 凭证键集合等共享常量（包私有）
│   └── LoggingContextHolder.java           — Spring 上下文静态桥接
├── request/
│   ├── RequestLogFilter.java               — HTTP 请求日志过滤器
│   └── IpCidrMatcher.java                  — 受信代理 CIDR 匹配（包私有）
└── metrics/
    ├── HttpMetricsRecorder.java            — HTTP 请求耗时指标（light-boot.http.requests）
    └── HttpMetricsFilter.java              — 采集过滤器
```

`AutoConfiguration.imports` 注册 `LoggingAutoConfiguration` 与 `LoggingObservabilityAutoConfiguration` 两个自动配置类。

---

## 核心设计

### TraceContext — TraceId 上下文

基于 `ThreadLocal` + `MDC` 双写。`set()` 同时写入两者，`clear()` 同时清理两者，防止泄漏。`generate()` 生成 32 位 UUID（去连字符）。

**生命周期**：由 `TraceFilter` 与 `ScheduledTraceAspect` 共同管理 — HTTP 请求与 `@Scheduled` 定时任务各自在进入时 `set()`，
在 `finally` 中 `clear()`（保证线程池复用不泄漏）。

### TraceFilter — 请求级 TraceId 注入

`OncePerRequestFilter` 实现，处理流程：

1. 从请求头 `X-Trace-Id` 读取上游 TraceId
2. 格式校验（`^[a-f0-9]{16,64}$`，仅小写十六进制），不合法则重新生成
3. 写入 `TraceContext` 和响应头（同名回写）
4. `finally` 块清理，防止线程池复用时泄漏

### ScheduledTraceAspect — 定时任务 TraceId

AOP 切面拦截 `@Scheduled` 方法，自动注入 **32 位纯 hex** TraceId（与 HTTP 请求一致，满足 `^[a-f0-9]{16,64}$` 契约，
便于定时任务向下游透传 `X-Trace-Id`），并以独立 MDC key `traceSource=scheduled` 标记来源（不参与透传与校验）。
条件加载（需要 AOP 依赖）。

### 日志脱敏

- **LogMasker** 接口：`String mask(String text)`，可自定义实现
- **DefaultLogMasker**：基于正则逐条匹配（按枚举序应用），性能优化：
    - 超长文本（>8192 字符）：仅跳过「无界」规则；当前内置规则均为「有界」，仍会被完整脱敏
    - 快速预判（`QUICK_CHECK`）：文本不含数字、`@`、且不含凭证键（password/passwd/secret/token/authorization/api_key 等）时直接跳过
- **MaskPattern** 枚举（按声明序应用，旧的中文姓名 `NAME` 已移除）：

| 规则                  | 效果                                      | 示例                             |
|---------------------|-----------------------------------------|--------------------------------|
| `PHONE`             | 前 3 后 4                                 | `138****8888`                  |
| `ID_CARD`           | 前 3 后 4                                 | `110***********1234`           |
| `BANK_CARD`         | 前 4 后 4                                 | `6222********1234`             |
| `EMAIL`             | 首字母 + 域名                                | `t***@example.com`             |
| `CREDENTIAL_BEARER` | 凭证键 + `bearer/basic/jwt <值>` 整体 → `******`（不保留 scheme 词） | `Authorization: ******`      |
| `CREDENTIAL_JSON`   | JSON 凭证键值对 → `"******"`                 | `"password":"******"`          |
| `CREDENTIAL`        | 凭证键后的任意值 → `******`                     | `password: ******`             |

> `CREDENTIAL_BEARER` / `CREDENTIAL_JSON` 先于通用 `CREDENTIAL` 执行，避免重复脱敏噪声。凭证键集合：
`password|passwd|secret|token|authorization|api[_-]?key`。通用 `CREDENTIAL` 的值为**贪婪** `\S+`：一直匹配到下一个空白字符为止，
引号、逗号、分号等分隔符会一并计入值被打码（宁可多脱敏、不漏脱敏）。

- **MaskingConverter**（`%maskMsg`）：Logback 自定义 Converter，DCL 初始化，优先从 Spring 容器获取自定义 `LogMasker`
  Bean，无则降级为默认实现；读取 `light-boot.logging.mask.enabled`，显式 `false` 时变为 no-op 并输出告警
  （该开关在 Spring 容器注入 `LoggingContextHolder` 后生效，容器就绪前的极早期日志仍按 `DefaultLogMasker` 脱敏且不告警；
  告警经 logback appender 重入保护可能被丢弃或跨 appender 重复，不保证恰好输出一次，单 appender 下可能不可见）
- **MaskingThrowableConverter**（`%maskEx`）：对异常栈脱敏，逻辑同上（无异常时原样返回空串）
- **MaskingStartupLogger**：应用就绪时若未声明完成接线，输出一次 WARN 提示（默认仅请求日志查询参数被脱敏，应用日志/异常栈需自行配置
  `%maskMsg`/`%maskEx`）。框架**不检测 Logback 运行时 layout**，是否真的接了 `%maskMsg`/`%maskEx` 由使用方自行保证；置
  `light-boot.logging.mask.wired=true` 即声明已完成接线并抑制该 WARN
- **LoggingContextHolder**：静态桥接，将 `ApplicationContext` 注入给非 Spring 管理的 Converter

### RequestLogFilter — HTTP 请求日志

`OncePerRequestFilter` 实现，记录：`HTTP {方法} {URI}{?参数} from {IP} -> {状态码} ({耗时}ms)`（慢请求末尾追加 `[SLOW]`）

- 排除路径支持（Ant 风格，默认排除 `/actuator/**`、`/favicon.ico`）
- 客户端 IP 解析：仅当 `remoteAddr` 命中 `light-boot.logging.request.trusted-proxies`（CIDR 列表，默认空=不信任任何代理）时，才解析
  `X-Forwarded-For` / `X-Real-IP` / `Proxy-Client-IP` / `WL-Proxy-Client-IP`；否则直接使用 TCP 连接地址，防止客户端伪造
- 查询参数处理顺序：CRLF/制表符清洗 → 脱敏 → 截断（超过 200 字符截断并标记 `...(truncated)`；
  脱敏在截断之前，避免敏感值被切断后部分泄漏；masker 未装配时查询串原样记录）
- **慢请求标记**：`light-boot.logging.request.slow-threshold-ms`（默认 1000ms），请求耗时 `>=` 阈值时日志级别提升为 WARN 并追加
  `[SLOW]`；设为 0 或负值禁用
- `System.nanoTime()` 精确计时
- 过滤器顺序：`TraceFilter`（`HIGHEST_PRECEDENCE`）→ `RequestLogFilter`（`+10`）→ `HttpMetricsFilter`（`+11`）

---

## Bean 注册清单

所有 Bean 均受模块总开关 `light-boot.logging.enabled=true`（默认）控制 —— 该开关同时作用于
`LoggingAutoConfiguration` 与 `LoggingObservabilityAutoConfiguration`（后者另需 `MeterRegistry` 在 classpath）。
除 `loggingContextHolderInitializer` 外均支持 `@ConditionalOnMissingBean` 覆盖。

| Bean                              | 条件加载                                                     | 说明                            |
|-----------------------------------|----------------------------------------------------------|-------------------------------|
| `loggingContextHolderInitializer` | 始终                                                       | 注入 Spring 上下文到静态桥接（返回占位 Bean，无 `@ConditionalOnMissingBean`） |
| `traceFilter`                     | Servlet API 在 classpath + `light-boot.logging.trace.enabled`   | Web 请求 TraceId（`@Order(HIGHEST_PRECEDENCE)`） |
| `requestLogFilter`                | Servlet API 在 classpath + `light-boot.logging.request.enabled` | 请求日志（`@Order(HIGHEST_PRECEDENCE + 10)`） |
| `logMasker`                       | `light-boot.logging.mask.enabled`                              | 默认正则脱敏，可覆盖                    |
| `maskingStartupLogger`            | `light-boot.logging.mask.enabled`                              | 应用就绪时未接线则 WARN 一次，可覆盖消音       |
| `scheduledTraceAspect`            | AOP 在 classpath                                          | 定时任务 TraceId（无独立开关）           |
| `mdcTaskDecorator`                | 始终                                                       | `@Async`/线程池 MDC 传播装饰器（无独立开关） |
| `httpMetricsRecorder`             | `MeterRegistry` 在 classpath（即引入 actuator）                | HTTP 耗时指标（`light-boot.http.requests`） |
| `httpMetricsFilter`               | 同上 + Servlet API 在 classpath                             | 指标采集过滤器（`@Order(HIGHEST_PRECEDENCE + 11)`） |

---

## 依赖

| 依赖                                  | 必选/可选 | 作用                                         |
|-------------------------------------|-------|--------------------------------------------|
| spring-boot-starter                 | 必选    | 自动配置、MDC、Logback                           |
| spring-boot-webmvc + jakarta.servlet-api | 可选 | TraceFilter / RequestLogFilter / HttpMetricsFilter（无 Web 时不加载） |
| spring-boot-starter-aspectj         | 可选    | ScheduledTraceAspect（无 AOP 时不加载）           |
| micrometer-core                     | 可选    | HttpMetricsRecorder / HttpMetricsFilter（无 MeterRegistry 时不加载） |
| spring-boot-configuration-processor | 可选    | IDE 配置提示                                   |
| lombok                              | 必需    | Properties 类简化（编译期）                        |

---

## 配置属性

前缀 `light-boot.logging`：

| 属性                          | 默认值                                | 说明                                                                                                   |
|-----------------------------|------------------------------------|------------------------------------------------------------------------------------------------------|
| `enabled`                   | `true`                             | 模块总开关                                                                                                |
| `trace.enabled`             | `true`                             | TraceId 开关                                                                                           |
| `trace.header-name`         | `X-Trace-Id`                       | 透传请求头名称                                                                                              |
| `request.enabled`           | `true`                             | 请求日志开关                                                                                               |
| `request.slow-threshold-ms` | `1000`                             | 慢请求阈值（ms）；耗时 `>=` 阈值记 WARN + `[SLOW]`，0/负值禁用                                                         |
| `request.exclude-paths`     | `["/actuator/**", "/favicon.ico"]` | 排除路径                                                                                                 |
| `request.trusted-proxies`   | `[]`                               | 受信代理 CIDR 列表；仅这些来源的转发头才被采信，空=不信任任何代理                                                                 |
| `mask.enabled`              | `true`                             | 脱敏开关                                                                                                 |
| `mask.wired`                | `false`                            | 是否已由使用方在 `logback-spring.xml` 完成 `%maskMsg`/`%maskEx` 接线；置 `true` 抑制 `MaskingStartupLogger` 的启动 WARN |

---

## 扩展点

| 扩展点            | 方式                                                                    |
|----------------|-----------------------------------------------------------------------|
| 自定义脱敏          | 注册 `LogMasker` Bean（`@ConditionalOnMissingBean` 自动让路）                 |
| 选择性脱敏          | `new DefaultLogMasker(List.of(MaskPattern.PHONE, MaskPattern.EMAIL))` |
| 自定义 TraceId 生成 | 覆盖 `TraceFilter` Bean                                                 |
| 自定义请求日志格式      | 覆盖 `RequestLogFilter` Bean                                            |

---

## 注意事项

- **ThreadLocal 泄漏**：`TraceFilter`（HTTP 请求）与 `ScheduledTraceAspect`（定时任务）的 `finally` 均保证清理；
  `@Async` 场景由 logging 模块的 `MdcTaskDecorator`（被 web 模块
  `AsyncConfig` 组合使用）传播 MDC
- **默认脱敏范围**：仅 HTTP 请求日志的查询参数由 `RequestLogFilter` 内部自动脱敏；应用日志消息/异常栈需在
  `logback-spring.xml` 注册并使用 `%maskMsg`/`%maskEx` 后才脱敏
- **启动 WARN 消音**：未接线时 `MaskingStartupLogger` 会在应用就绪时告警一次。确认已接线后置
  `light-boot.logging.mask.wired=true` 声明并消音（推荐，显式声明无运行时误判）；也可注册同名 Bean 覆盖，或置
  `light-boot.logging.mask.enabled=false`（同时关闭脱敏本身）。框架不检测 Logback 运行时 layout，未实际接线却置 `wired=true`
  会掩盖「应用日志未脱敏」的事实
- **性能**：脱敏按需匹配（快速预判跳过无敏感特征文本）；TraceFilter 为亚毫秒级开销（经验值，仓库未附基准测试）
- **兼容性**：支持 Servlet（Spring MVC）；不支持 WebFlux；部分支持非 Web 应用（脱敏可用）
