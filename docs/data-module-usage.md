# data — 数据模块使用指南

> 适用版本：lightboot 1.0.0 / Spring Boot 4.1.1 / Java 21+
>
> 设计原理见 [data 模块设计](./data-module-design.md)。

---

## 目录

- [模块简介](#模块简介)
- [引入方式](#引入方式)
- [快速开始](#快速开始)
- [功能详解](#功能详解)
    - [分页查询](#分页查询)
    - [分页 + 排序查询](#分页--排序查询)
    - [带汇总的分页查询](#带汇总的分页查询)
- [参数校验说明](#参数校验说明)
- [最佳实践](#最佳实践)
- [常见问题](#常见问题)
- [版本与兼容性](#版本与兼容性)

---

## 模块简介

`lightboot-data` 提供标准化的分页和排序数据结构：

| 类                   | 用途                |
|---------------------|-------------------|
| `PageParam`         | 分页请求参数（页码 + 每页条数） |
| `SortParam`         | 排序参数（字段 + 方向）     |
| `PageSortParam`     | 分页 + 排序组合参数       |
| `Page<T>`           | 分页查询结果            |
| `Summary`           | 汇总数据基类            |
| `PageSummary<T, S>` | 带汇总的分页结果          |

---

## 引入方式

```xml
<dependency>
    <groupId>cn.nextdev</groupId>
    <artifactId>lightboot-data</artifactId>
</dependency>
```

如果项目已通过 BOM 方式引入 `lightboot`，无需指定版本号。

> **注意：** `lightboot-data` 不依赖 `lightboot-core`。下文示例中返回的 `Result` 来自 `lightboot-core`，需一并引入
> （或直接使用 `lightboot-web`，由其传递引入）。

---

## 快速开始

### 1. 定义分页查询接口

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping
    public Result<Page<UserVO>> listUsers(PageParam pageParam) {
        Page<UserVO> page = userService.listUsers(pageParam);
        return Result.success(page);
    }
}
```

### 2. 请求示例

```bash
GET /api/users?current=2&pageSize=10
```

### 3. 响应示例

```json
{
    "code": 0,
    "message": "操作成功",
    "data": {
        "records": [
            {"id": 11, "name": "张三"},
            {"id": 12, "name": "李四"}
        ],
        "total": 100,
        "pageSize": 10,
        "current": 2,
        "totalPage": 10
    },
    "traceId": "0123456789abcdef0123456789abcdef"
}
```

> **说明：** traceId 由请求链路自动填充；非 web 场景或无 traceId 时该字段整体省略。

---

## 功能详解

### 分页查询

#### Controller 接收参数

```java
@GetMapping
public Result<Page<UserVO>> listUsers(PageParam pageParam) {
    // pageParam.getCurrent()  →  2
    // pageParam.getPageSize() →  10
    // pageParam.getOffset()   →  10  (即 (2-1)*10)
    return Result.success(userService.listUsers(pageParam));
}
```

> Spring MVC 自动将请求参数 `current` 和 `pageSize` 绑定到 `PageParam` 字段。`PageParam` 有默认值（current=1,
> pageSize=20），不传参时也能正常工作。范围校验需在形参前加 `@Valid` 才会生效（见 [参数校验说明](#参数校验说明)）。

#### Service 组装结果

```java
@Service
public class UserService {

    public Page<UserVO> listUsers(PageParam pageParam) {
        // 1. 查询当前页数据
        List<User> users = userRepository.findPage(
            pageParam.getOffset(), pageParam.getPageSize());

        // 2. 查询总记录数
        long total = userRepository.count();

        // 3. 组装分页结果（使用工厂方法，totalPage 自动计算）
        List<UserVO> vos = users.stream()
            .map(UserConverter::toVO)
            .toList();
        return Page.of(vos, total, pageParam);
    }
}
```

> `Page` 构造时会对 `records` 做防御性拷贝并包装为不可修改列表：外部修改原始 list 不影响已构建的 `Page`，
> 调用 `page.getRecords().add(...)` 会抛出 `UnsupportedOperationException`。

#### MyBatis-Plus 集成

如果使用 MyBatis-Plus，可利用 `PageParam` 的 `getOffset()` 和 `getPageSize()`：

```java
public Page<UserVO> listUsers(PageParam pageParam) {
    // MyBatis-Plus 的 Page 对象
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<User> mpPage =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(
            pageParam.getCurrent(), pageParam.getPageSize());

    mpPage = userMapper.selectPage(mpPage, queryWrapper);

    List<UserVO> vos = mpPage.getRecords().stream()
        .map(UserConverter::toVO)
        .toList();
    return Page.of(vos, mpPage.getTotal(), pageParam);
}
```

### 分页 + 排序查询

#### Controller

```java
@GetMapping
public Result<Page<UserVO>> listUsers(@Valid PageSortParam pageSortParam) {
    return Result.success(userService.listUsers(pageSortParam));
}
```

#### 请求示例

```bash
GET /api/users?pageParam.current=1&pageParam.pageSize=10&sortParam.sortField=create_time&sortParam.sortType=desc
```

> `PageSortParam` 的 `pageParam` 有默认值（new PageParam()），即使不传分页参数也不会 NPE。`sortParam` 为可选，不传时为 null。

#### Service

```java
public Page<UserVO> listUsers(PageSortParam param) {
    SortParam sort = param.getSortParam();
    String orderBy = sort != null
        ? sort.getSortField() + " " + sort.getSortType()
        : "id DESC";

    List<User> users = userRepository.findPage(
        param.getOffset(), param.getPageParam().getPageSize(), orderBy);
    long total = userRepository.count();

    return Page.of(users.stream().map(UserConverter::toVO).toList(),
                   total, param.getPageParam());
}
```

> **安全提示：** 仅当 Controller 形参标注 `@Valid`/`@Validated` 时，`sortField` 才会被校验为「字母或下划线开头，仅字母、数字和
> 下划线」——这只是**格式**校验，不等于列名合法性校验（校验通过的字段名仍可能是 `password` 等敏感列）。持久层应坚持参数化或白名单映射，
> 避免直接拼接 SQL。

### 带汇总的分页查询

#### 1. 定义汇总类

```java
@Getter
@Setter
public class OrderSummary extends Summary {

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 平均订单金额 */
    private BigDecimal avgAmount;

    public OrderSummary(long total, BigDecimal totalAmount, BigDecimal avgAmount) {
        this.total = total;  // Summary 的 total 字段为 protected，可直接赋值
        this.totalAmount = totalAmount;
        this.avgAmount = avgAmount;
    }
}
```

#### 2. Service 组装结果

```java
public PageSummary<OrderVO, OrderSummary> listOrders(PageParam pageParam) {
    // 查询分页数据
    List<Order> orders = orderRepository.findPage(
        pageParam.getOffset(), pageParam.getPageSize());

    // 查询汇总数据
    OrderSummary summary = orderRepository.selectSummary();

    List<OrderVO> vos = orders.stream().map(OrderConverter::toVO).toList();
    return PageSummary.of(vos, summary, pageParam);
}
```

#### 3. 响应示例（带汇总）

```json
{
    "code": 0,
    "message": "操作成功",
    "data": {
        "records": [...],
        "total": 1000,
        "pageSize": 20,
        "current": 1,
        "totalPage": 50,
        "summary": {
            "total": 1000,
            "totalAmount": 99999.99,
            "avgAmount": 99.99
        }
    },
    "traceId": "0123456789abcdef0123456789abcdef"
}
```

> **说明：** traceId 由请求链路自动填充；非 web 场景或无 traceId 时该字段整体省略。

---

## 参数校验说明

所有参数校验使用 Jakarta Validation 注解，需配合 `@Valid` 或 `@Validated` 使用：

| 类           | 字段          | 校验规则                                                       | 错误消息                         |
|-------------|-------------|------------------------------------------------------------|------------------------------|
| `PageParam` | `current`   | `@Min(1)`, `@Max(1_000_000)`                               | 页码最小值为 1 / 页码超出允许范围          |
| `PageParam` | `pageSize`  | `@Min(1)`, `@Max(100)`                                     | 每页条数最小值为 1 / 每页条数最大值为 100    |
| `SortParam` | `sortField` | `@Pattern("[a-zA-Z_][a-zA-Z0-9_]{0,62}")`, `@Size(max=63)` | 排序字段必须以字母或下划线开头，仅支持字母、数字和下划线 |
| `SortParam` | `sortType`  | `@Pattern("asc\|desc", flags=CASE_INSENSITIVE)`            | 排序方向必须为 asc 或 desc           |

**启用校验：**

```java
@GetMapping
public Result<Page<UserVO>> listUsers(@Valid PageParam pageParam) {
    // ...
}
```

> web 模块的 `GlobalExceptionHandler` 会自动捕获 `BindException`/`MethodArgumentNotValidException` 校验异常并返回标准错误响应，
> 详见 [web 模块使用指南](./web-module-usage.md)。

---

## 最佳实践

### 1. 使用 Page.of() 工厂方法

```java
// ✅ 推荐：使用工厂方法，确保 totalPage 自动计算
return Page.of(records, total, pageParam);

// ✅ 无需精确分页元数据时（仅返回 records + total；元数据为字段默认值 current=1、pageSize=0、totalPage=0，并非 PageParam 的 1/20）
return Page.of(records, total);

// ❌ 不推荐：手动创建（注意构造方法参数顺序：current, pageSize, total, records）
return new Page<>(pageParam.getCurrent(), pageParam.getPageSize(), total, records);
```

### 2. 排序字段使用白名单映射

```java
// ✅ 推荐：字段名映射
private static final Map<String, String> SORT_FIELD_MAP = Map.of(
    "createTime", "create_time",
    "updateTime", "update_time",
    "name", "name"
);

String column = SORT_FIELD_MAP.getOrDefault(sortParam.getSortField(), "id");

// ❌ 不推荐：直接使用用户输入的字段名
String column = sortParam.getSortField(); // 虽然有正则校验，但仍不够安全
```

### 3. 前端请求参数命名

```text
# 分页参数（PageParam 有默认值，可不传）
?current=1&pageSize=20

# 分页 + 排序参数（嵌套对象，sortParam 可选）
?pageParam.current=1&pageParam.pageSize=20&sortParam.sortField=name&sortParam.sortType=asc
```

---

## 常见问题

### 分页参数不生效？

`PageParam` 有默认值（current=1, pageSize=20），即使前端不传参数也能正常工作。如需校验范围，在 Controller 方法参数前加
`@Valid`。

### 排序参数中 sortField 报校验错误？

`sortField` 必须以字母或下划线开头，后接字母、数字、下划线（正则 `[a-zA-Z_][a-zA-Z0-9_]{0,62}`，长度上限 63）。`create_time`、
`createTime` 合法；但 `create-time`（含连字符）、`123field`（数字开头）、`createTime ASC`（含空格）会校验失败。

### Page 和 MyBatis-Plus 的 Page 冲突？

注意区分导入：

- 框架的：`cn.nextdev.lightboot.data.pagination.Page`
- MyBatis-Plus 的：`com.baomidou.mybatisplus.extension.plugins.pagination.Page`

建议在 Service 层完成转换，对外暴露框架的 `Page`。

### 如何自定义每页最大条数？

`PageParam` 的 `@Max(100)` 硬编码在注解中。Bean Validation 会校验整个类层次（含父类私有字段）的约束，**继承 `PageParam`
无法覆盖或移除该注解**——子类重新声明同名字段只会遮蔽父类字段，父类的 `@Max(100)` 仍然生效。可行做法：自定义独立的参数类，
或在 Service 层做额外校验。

### PageSummary 的 summary 为 null？

`PageSummary.of()` 内部会读取 `summary.getTotal()` 作为总记录数，因此 `summary` 为 null 时会抛出 `NullPointerException`，
请确保传入非空对象。需要允许"无汇总"的场景，请改用五参构造方法 `new PageSummary<>(current, pageSize, total, records, summary)`
（其 `summary` 可为 null）。

---

## 版本与兼容性

- 适用 lightboot `1.0.0` / Spring Boot 4.1.1 / Java 21+（与 `lightboot-bom` 一致）
- `Page.of(records, total)`（两参）不计算分页元数据：`current=1、pageSize=0、totalPage=0`，并非 `PageParam` 的默认 1/20；
  需要完整元数据请用 `Page.of(records, total, pageParam)`
- `Page` 构造时对 `records` 防御性拷贝，`getRecords()` 返回不可修改视图（见上文分页查询一节）
- 校验注解需 `@Valid`/`@Validated` 且运行时存在 Bean Validation 实现（如 hibernate-validator）才会生效；
  `PageParam` 的 `@Max(100)` 无法通过继承覆盖（见上文常见问题）
- 本模块不依赖 `lightboot-core`，示例中的 `Result` 需另行引入（见上文引入方式）

