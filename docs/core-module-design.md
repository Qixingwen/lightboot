# core 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+
>
> 用法与示例见 [core 模块使用指南](./core-module-usage.md)。

---

## 定位

框架的基础模块，为依赖它的模块（当前为 `lightboot-web`、`lightboot-redis`、`lightboot-ratelimit`；`lightboot-data`、`lightboot-logging` 不依赖本模块）提供统一的
**API 响应规范**、**错误码体系** 和 **异常处理机制**。编译期仅用 Lombok，运行时仅依赖 slf4j-api 与 jackson-annotations（均无传递依赖），处于依赖链最底层。

---

## 模块结构

```text
cn.nextdev.lightboot.core/
├── api/
│   ├── IErrorCode.java        — 错误码接口（getCode / getMessage）
│   ├── ResultCode.java        — 内置错误码枚举（SUCCESS / FAILED / UNAUTHORIZED 等）
│   └── Result.java            — 统一 API 响应（code + message + data + traceId）
└── exception/
    ├── ApiException.java       — 结构化异常（携带 IErrorCode）
    └── Asserts.java            — 断言工具（notNull / isTrue / fail）
```

**5 个 Java 文件**，无资源文件。

---

## 核心设计

### Result\<T\> — 统一响应

- **不可变**：所有字段 `final`，天然线程安全
- **泛型**：`data` 支持任意类型
- **traceId**：构造时自动从 MDC 读取（key `traceId`，由 `TraceFilter`/`TraceContext` 写入）；无值时序列化整体省略；属传输上下文，不参与 `equals`/`hashCode`
- **静态工厂方法**：

| 类型   | 方法                                         | 说明              |
|------|--------------------------------------------|-----------------|
| 成功   | `success()`                                | 无数据             |
|      | `success(T data)`                          | 携带数据            |
|      | `success(T data, String message)`              | 携带数据 + 自定义消息    |
|      | `success(IErrorCode errorCode)`            | 使用错误码的 code/message |
|      | `success(IErrorCode errorCode, T data)`    | 错误码 + 数据        |
| 失败   | `failed(String message)`                       | 自定义消息（code=-1）  |
|      | `failed(IErrorCode errorCode)`             | 使用错误码           |
|      | `failed(IErrorCode errorCode, String message)` | 错误码 + 覆盖消息      |
|      | `failed(IErrorCode errorCode, T data)`     | 错误码 + 携带数据      |
| HTTP | `badRequest(String message)`                   | 40000 + 自定义消息   |
|      | `unauthorized()`                           | 40100           |
|      | `forbidden()`                              | 40300           |

实例方法：

| 方法          | 说明                                                    |
|-------------|-------------------------------------------------------|
| `isSuccess()` | 严格判断 `code == 0`（即 `ResultCode.SUCCESS` 的编码）       |
| `failed()`    | 即 `!isSuccess()`，code 不为 0 时返回 true                  |

> **注意：** `isSuccess()` 严格比较 `code == 0`。即使通过 `success(IErrorCode)` 传入自定义错误码（例如自定义非零成功码），
> 只要 code 不为 0，`isSuccess()` 也会返回 `false`。这是设计上的严格定义。

响应格式：

```json
{ "code": 0, "message": "操作成功", "data": { ... }, "traceId": "0123456789abcdef0123456789abcdef" }
```

> **说明：** `traceId` 由 web 请求链路自动填充，用于响应体与日志的关联排查；非 web 场景或链路断裂时该字段整体省略（`data` 为 null 时仍正常输出）。

### IErrorCode + ResultCode — 错误码体系

`IErrorCode` 接口仅两个方法：`getCode()` 和 `getMessage()`。`ResultCode` 枚举提供内置错误码：

| 错误码   | 枚举值              | 含义           |
|-------|------------------|--------------|
| 0     | `SUCCESS`        | 成功           |
| -1    | `FAILED`         | 通用失败         |
| 10001 | `AUTH_FAILED`    | 账号或密码错误      |
| 40000 | `BAD_REQUEST`    | 请求参数错误或格式无效  |
| 40100 | `UNAUTHORIZED`   | 登录会话失效，请重新登录 |
| 40300 | `FORBIDDEN`      | 没有相关权限       |
| 40400 | `NOT_FOUND`      | 请求的资源不存在     |
| 40900 | `CONFLICT`       | 数据冲突，资源已存在   |
| 41300 | `FILE_TOO_LARGE` | 文件大小超过限制      |
| 50000 | `INTERNAL_ERROR` | 系统内部错误       |

业务模块通过枚举实现 `IErrorCode` 定义领域错误码（建议从 60000 起）。

### ApiException — 结构化异常

携带 `IErrorCode`，由 web 模块的 `GlobalExceptionHandler`（见 [web 模块设计](./web-module-design.md)）统一拦截并转为 `Result` 响应。

**Retry-After 钩子：** `ApiException` 提供 `getRetryAfterSeconds()`（默认返回 `0L`）。web 模块的 `GlobalExceptionHandler`
在该值 `> 0` 时会写入 `Retry-After` 响应头。限流场景下子类 `RateLimitException` 覆盖该方法返回窗口秒数，从而自动带上
`Retry-After`。

构造方法：

| 构造方法                                                              | 说明                    |
|-------------------------------------------------------------------|-----------------------|
| `ApiException(IErrorCode errorCode)`                              | 使用错误码的 message            |
| `ApiException(String message)`                                        | 自定义消息（errorCode=null） |
| `ApiException(Throwable cause)`                                   | 包装原始异常                |
| `ApiException(IErrorCode errorCode, String message)`                  | 错误码 + 覆盖消息            |
| `ApiException(String message, Throwable cause)`                       | 消息 + 原始异常             |
| `ApiException(IErrorCode errorCode, String message, Throwable cause)` | 完整参数                  |

```text
业务代码 → throw ApiException → GlobalExceptionHandler → Result.failed()
```

### Asserts — 断言工具

替代 `if-throw` 模式，7 个静态方法。所有重载均成对提供（`IErrorCode` 版本 + `String message` 版本），仅 `isFalse` 例外（仅支持
`IErrorCode`）：

| 方法                             | 说明                          |
|--------------------------------|-----------------------------|
| `fail(String message)`             | 直接抛出带消息的 ApiException       |
| `fail(IErrorCode errorCode)`   | 直接抛出带错误码的 ApiException      |
| `notNull(Object, IErrorCode)`  | 非空校验，对象为 null 时抛出           |
| `notNull(Object, String message)`  | 非空校验（自定义消息）                 |
| `isTrue(boolean, IErrorCode)`  | 条件为 false 时抛出（即断言表达式为 true） |
| `isTrue(boolean, String message)`  | 条件为 false 时抛出（自定义消息）        |
| `isFalse(boolean, IErrorCode)` | 条件为 true 时抛出（即断言表达式为 false） |

> 注意：`notNull`/`isTrue` 的校验语义是"表达式为假时抛出"，`isFalse` 的语义是"表达式为真时抛出"。

---

## 类关系

```text
IErrorCode ◄── ResultCode (enum)
     │             业务自定义 enum
     │
     ├─► Result<T>         ApiException ◄── Asserts
     │    code/message/data      errorCode
     │                       │
     └───────────────────────┘ throw → GlobalExceptionHandler → Result.failed()
```

---

## 依赖

| 依赖     | 必选/可选 | 作用                                                        |
|--------|-------|-----------------------------------------------------------|
| lombok | 必需    | 简化 getter（`Result`、`ApiException` 等均使用 `@Getter`），编译期注解处理（经父 pom annotationProcessorPaths），optional 不向下游传递 |
| slf4j-api | 必需    | `Result` 构造时读取 MDC 获取 traceId；仅日志门面，无传递依赖 |
| jackson-annotations | 必需 | `@JsonInclude(NON_NULL)` 控制 traceId 无值时省略；纯注解包，无传递依赖 |

不依赖 Spring 或任何第三方框架。运行时仅依赖 slf4j-api 与 jackson-annotations 两个无传递依赖的基础包，可在纯 Java 项目中使用。

---

## 扩展点

| 扩展点    | 方式                              | 示例                                               |
|--------|---------------------------------|--------------------------------------------------|
| 自定义错误码 | 枚举实现 `IErrorCode`               | `OrderErrorCode.ORDER_NOT_FOUND(60001, "订单不存在")` |
| 自定义异常  | 继承 `ApiException`               | 添加字段如错误详情列表                                      |
| 全局异常处理 | web 模块 `GlobalExceptionHandler` | 已内置，自动拦截 `ApiException`（见 [web 模块设计](./web-module-design.md)） |

---

## 注意事项

- `Result<T>` 不可变，线程安全
- 需配合 web 模块的 `GlobalExceptionHandler` 才能自动转响应
- core 可在非 Spring 项目中使用
- `ApiException` 的 `errorCode` 可为 null（使用 `String message` 构造时），web 模块会降级使用 `ResultCode.FAILED`

---

## 版本与兼容性

- 本模块当前版本 `1.0.0`，与 `lightboot-bom` 版本一致，Java 21+（与父 pom 的 `java.version=21` 一致，经 compiler 插件 `<release>` 生效）
- core 不依赖 Spring 或任何第三方框架（编译期仅 Lombok，运行时仅 slf4j-api 与 jackson-annotations 两个无传递依赖的基础包），因此**不绑定特定 Spring Boot 版本**；文档头部标注的
  `Spring Boot 4.1.1` 仅为本框架整体的验证基线，并非 core 的运行前提
- `Result` 实现 `java.io.Serializable`（`serialVersionUID = 1L`），可安全参与 Java 序列化
- `Result.isSuccess()` 自 1.0.0 起严格定义为 `code == 0`，使用 `success(IErrorCode)` 传入非零 code 会被判定为失败，后续版本不会放宽该语义
- `Asserts` 的重载集合（7 个静态方法，`isFalse` 仅 `IErrorCode` 版本）为稳定 API；新增重载会在版本说明中标注
