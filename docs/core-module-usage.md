# core — 核心模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+
>
> 设计原理与类关系见 [core 模块设计](./core-module-design.md)。

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速开始](#快速开始)
- [功能详解](#功能详解)
    - [统一响应 Result](#统一响应-result)
    - [错误码体系](#错误码体系)
    - [API 异常](#api-异常)
    - [断言工具 Asserts](#断言工具-asserts)
- [最佳实践](#最佳实践)
- [常见问题](#常见问题)
- [版本与兼容性](#版本与兼容性)

---

## 模块简介

`lightboot-core` 提供框架的基础能力：

| 能力        | 说明                                  |
|-----------|-------------------------------------|
| 统一 API 响应 | `Result<T>` 封装，所有接口返回一致的 JSON 结构    |
| 错误码体系     | `IErrorCode` 接口 + `ResultCode` 内置枚举 |
| 结构化异常     | `ApiException` 携带错误码，配合全局异常处理器使用    |
| 断言工具      | `Asserts` 简化业务校验代码                  |

**设计原则：零框架依赖** — 编译期仅依赖 Lombok，运行时仅依赖 slf4j-api 与 jackson-annotations 两个无传递依赖的基础包，不依赖 Spring 或其他框架。

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-core</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

> 通常 core 作为其他模块的传递依赖自动引入，无需单独添加。

---

## 快速开始

### 1. Controller 返回统一响应

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public Result<UserVO> getUser(@PathVariable Long id) {
        UserVO user = userService.getById(id);
        return Result.success(user);
    }

    @PostMapping
    public Result<Void> createUser(@RequestBody @Valid UserCreateDTO dto) {
        userService.create(dto);
        return Result.success();
    }
}
```

**响应格式：**

```json
// 成功
{
    "code": 0,
    "message": "操作成功",
    "data": {
        "id": 1,
        "name": "张三"
    },
    "traceId": "0123456789abcdef0123456789abcdef"
}

// 失败
{
    "code": 40400,
    "message": "请求的资源不存在",
    "data": null,
    "traceId": "0123456789abcdef0123456789abcdef"
}
```

> **说明：** `traceId` 由 lightboot-web 的请求链路自动填充（来源 `TraceFilter` / MDC），用于日志关联与问题排查；非 web 场景或无 traceId 时该字段整体省略。

### 2. Service 层抛出业务异常

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

异常由 web 模块的 `GlobalExceptionHandler` 统一拦截，自动转换为 `Result.failed()` 响应（详见
[web 模块使用指南](./web-module-usage.md)）。

---

## 功能详解

### 统一响应 Result

#### 创建成功响应

```java
// 无数据返回
Result<Void> result = Result.success();

// 携带数据
Result<UserVO> result = Result.success(userVO);

// 携带数据 + 自定义消息
Result<UserVO> result = Result.success(userVO, "查询成功");

// 使用 IErrorCode 的 code/message
Result<Void> result = Result.success(ResultCode.SUCCESS);

// 使用 IErrorCode + 携带数据
Result<UserVO> result = Result.success(ResultCode.SUCCESS, userVO);
```

#### 创建失败响应

```java
// 自定义消息（code 默认 -1）
Result<Void> result = Result.failed("余额不足");

// 使用错误码
Result<Void> result = Result.failed(ResultCode.BAD_REQUEST);

// 错误码 + 覆盖消息
Result<Void> result = Result.failed(ResultCode.BAD_REQUEST, "用户名格式不正确");

// 错误码 + 携带数据（ErrorDetail 为自定义 POJO，见最佳实践 4）
Result<ErrorDetail> result = Result.failed(ResultCode.BAD_REQUEST, errorDetail);
```

#### HTTP 状态快捷方法

```java
Result<Void> result = Result.badRequest("参数不合法");  // 40000 + 自定义消息
Result<Void> result = Result.unauthorized();             // 40100
Result<Void> result = Result.forbidden();                // 40300
```

> **注意：** `badRequest(String message)` 需要传入自定义消息，不是无参方法。

#### 判断成功 / 失败

```java
Result<UserVO> result = Result.success(userVO);

if (result.isSuccess()) {   // 严格判断 code == 0
    // ...
}

if (result.failed()) {      // 等价于 !isSuccess()
    // ...
}
```

> **注意：** `isSuccess()` 严格比较 `code == 0`。通过 `Result.success(IErrorCode)` 传入自定义**非零**错误码时，
> 即便是"成功"工厂方法创建的结果，`isSuccess()` 也会返回 `false`。

### 错误码体系

#### 内置错误码

| 错误码   | 枚举值              | 说明           |
|-------|------------------|--------------|
| 0     | `SUCCESS`        | 操作成功         |
| -1    | `FAILED`         | 操作失败（通用）     |
| 10001 | `AUTH_FAILED`    | 账号或密码错误      |
| 40000 | `BAD_REQUEST`    | 请求参数错误或格式无效  |
| 40100 | `UNAUTHORIZED`   | 登录会话失效，请重新登录 |
| 40300 | `FORBIDDEN`      | 没有相关权限       |
| 40400 | `NOT_FOUND`      | 请求的资源不存在     |
| 40900 | `CONFLICT`       | 数据冲突，资源已存在   |
| 41300 | `FILE_TOO_LARGE` | 文件大小超过限制      |
| 50000 | `INTERNAL_ERROR` | 系统内部错误       |

#### 自定义业务错误码

```java
@Getter
@AllArgsConstructor
public enum OrderErrorCode implements IErrorCode {

    ORDER_NOT_FOUND(60001, "订单不存在"),
    ORDER_STATUS_ERROR(60002, "订单状态不允许此操作"),
    STOCK_NOT_ENOUGH(60003, "库存不足"),
    PAYMENT_TIMEOUT(60004, "支付超时");

    private final long code;
    private final String message;
}
```

**错误码规划建议：**

| 范围          | 用途    |
|-------------|-------|
| 0           | 成功    |
| -1          | 通用失败  |
| 10001-19999 | 认证/登录 |
| 20001-39999 | 框架预留  |
| 40000-49999 | 客户端错误 |
| 50000-59999 | 服务端错误 |
| 60000+      | 业务自定义 |

> **注意：** 自定义错误码不在 web 模块 `HttpStatusCodeResolver` 的显式映射表中时，异常响应的 HTTP 状态码默认为 500
> （响应体中的 `Result.code` 仍按业务码区分）。如需精确的 HTTP 状态码映射，见
> [web 模块设计](./web-module-design.md) 的「HTTP 状态码解析」。

### API 异常

```java
// 方式一：使用错误码（message 取自 errorCode）
throw new ApiException(ResultCode.UNAUTHORIZED);

// 方式二：自定义消息（errorCode 为 null）
throw new ApiException("操作失败：余额不足");

// 方式三：错误码 + 覆盖消息
throw new ApiException(ResultCode.BAD_REQUEST, "用户名不能为空");

// 方式四：包装原始异常
throw new ApiException("调用外部服务失败", remoteException);

// 方式五：完整参数
throw new ApiException(ResultCode.INTERNAL_ERROR, "支付网关异常", cause);
```

### 断言工具 Asserts

`Asserts` 替代冗长的 `if-throw` 模式，让业务校验代码更简洁：

```java
// ===== 对象非空校验 =====

// 传统写法
if (user == null) {
    throw new ApiException(ResultCode.NOT_FOUND, "用户不存在");
}

// Asserts 写法
Asserts.notNull(user, "用户不存在");
Asserts.notNull(order, OrderErrorCode.ORDER_NOT_FOUND);

// ===== 布尔条件校验 =====

// 条件必须为 true
Asserts.isTrue(order.isPayable(), "订单不可支付");
Asserts.isTrue(hasPermission, ResultCode.FORBIDDEN);

// 条件必须为 false
Asserts.isFalse(order.isExpired(), OrderErrorCode.ORDER_STATUS_ERROR);

// ===== 直接抛出失败 =====
Asserts.fail("不支持的操作");
Asserts.fail(OrderErrorCode.STOCK_NOT_ENOUGH);
```

---

## 最佳实践

### 1. Controller 层只做 Result 包装

```java
// ✅ 推荐
@GetMapping("/{id}")
public Result<UserVO> getUser(@PathVariable Long id) {
    return Result.success(userService.getById(id));
}

// ❌ 不推荐：Controller 层做业务校验
@GetMapping("/{id}")
public Result<UserVO> getUser(@PathVariable Long id) {
    UserVO user = userService.getById(id);
    if (user == null) {
        return Result.failed(ResultCode.NOT_FOUND);
    }
    return Result.success(user);
}
```

### 2. Service 层使用 Asserts + ApiException

```java
// ✅ 推荐：校验和异常在 Service 层
@Service
public class OrderService {
    public void cancel(Long orderId, Long userId) {
        Order order = orderRepository.findById(orderId);
        Asserts.notNull(order, "订单不存在");
        Asserts.isTrue(order.getUserId().equals(userId), "无权操作此订单");
        Asserts.isTrue(order.isCancellable(), "订单状态不允许取消");

        order.cancel();
        orderRepository.save(order);
    }
}
```

### 3. 使用自定义错误码枚举

```java
// ✅ 推荐：业务模块定义专属错误码
Asserts.notNull(user, UserErrorCode.USER_NOT_FOUND);

// ❌ 不推荐：到处硬编码错误消息
Asserts.notNull(user, "用户不存在");
```

### 4. 利用 failed(IErrorCode, T data) 附加错误详情

```java
// 自定义错误详情 POJO（core 不依赖 Spring，请勿使用 Spring 的 FieldError 类）
public record ErrorDetail(String field, String message) {
}

// 返回校验错误的具体字段信息
List<ErrorDetail> errors = List.of(new ErrorDetail("email", "格式不正确"));
return Result.failed(ResultCode.BAD_REQUEST, errors);
```

> web 模块的 `GlobalExceptionHandler` 对 `@Valid` 校验失败的处理是把字段错误拼接为消息文本，见
> [web 模块设计](./web-module-design.md)。

---

## 常见问题

### 可以不使用 Result 包装直接返回数据吗？

可以，但推荐统一使用 `Result<T>`。如果直接返回对象，会失去统一的响应格式（code/message/data 结构），前端需要不同的解析逻辑。

### 自定义错误码和内置错误码会冲突吗？

不会。只要错误码数字不重复即可。建议业务错误码从 60000 开始，避开内置的 0、-1、10001、40000-50000 范围。

### ApiException 和 RuntimeException 有什么区别？

`ApiException` 携带结构化的 `IErrorCode`，可被 web 模块的 `GlobalExceptionHandler` 统一拦截并转换为标准 `Result` 响应。普通
`RuntimeException`（及其它未被特判的异常）走兜底处理器，返回 code=-1（消息「运行时处理异常」，HTTP 500）；仅 `NullPointerException`
被特判为 code=50000（INTERNAL_ERROR）。完整映射表见 [web 模块设计](./web-module-design.md)。

### badRequest() 和 failed(ResultCode.BAD_REQUEST) 有什么区别？

`badRequest(String message)` 必须传入自定义消息。`failed(ResultCode.BAD_REQUEST)` 使用默认消息"请求参数错误或格式无效"。两者
code 相同（40000）。

### core 模块可以在非 Spring 项目中使用吗？

可以。core 模块不依赖 Spring，仅提供 `Result`、`IErrorCode`、`ApiException`、`Asserts` 等纯 Java 工具类。但全局异常处理等高级功能需要配合
web 模块使用（见 [web 模块使用指南](./web-module-usage.md)）。

---

## 版本与兼容性

- 适用 lightboot `1.0.0`（与 `lightboot-bom` 一致）；`Spring Boot 4.1.1 / Java 21+` 为框架整体验证基线
- core 不依赖 Spring（编译期仅 Lombok，运行时仅 slf4j-api 与 jackson-annotations），任何 Spring Boot 版本均可配合使用，也可在纯 Java 项目中使用
- `Result.isSuccess()` 严格定义为 `code == 0`（见上文「判断成功 / 失败」的注意项），属长期语义
- 自定义错误码经 web 模块映射 HTTP 状态码时，未登记的码默认返回 HTTP 500（详见上文「错误码规划建议」的注意项）

