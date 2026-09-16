# data 模块设计

> 版本：1.0.0 · lightboot / Spring Boot 4.1.1 / Java 21+
>
> 用法与示例见 [data 模块使用指南](./data-module-usage.md)。

---

## 定位

数据访问辅助模块，提供标准化的 **分页查询** 和 **排序** 参数封装。轻量（仅 Jakarta Validation + Lombok），不依赖任何持久层框架。

---

## 模块结构

```text
cn.nextdev.lightboot.data/
├── pagination/
│   ├── PageParam.java         — 分页请求参数（current + pageSize）
│   ├── Page.java              — 分页结果容器（records + total + pageSize + current + totalPage）
│   ├── PageSortParam.java     — 分页 + 排序组合参数
│   ├── PageSummary<T,S>.java  — 带汇总数据的分页结果
│   └── Summary.java           — 汇总基类（total）
└── sort/
    └── SortParam.java         — 排序参数（sortField + sortType）
```

**6 个 Java 文件**，无资源文件。

---

## 核心设计

### PageParam — 分页请求

| 字段         | 类型     | 默认值 | 校验                                                                                |
|------------|--------|-----|-----------------------------------------------------------------------------------|
| `current`  | `long` | 1   | `@Min(value=1, message="页码最小值为 1")`, `@Max(value=1_000_000L, message="页码超出允许范围")` |
| `pageSize` | `long` | 20  | `@Min(value=1, message="每页条数最小值为 1")`, `@Max(value=100, message="每页条数最大值为 100")`  |

提供 `getOffset()` 方法：`(current - 1) * pageSize`，供数据库查询直接使用。带溢出保护：`current <= 1` 或 `pageSize <= 0` 时返回
`0`；乘法溢出时钳制为 `Long.MAX_VALUE`，绝不返回负值。

> `current` 的 `@Max(1_000_000)` 用于防止深分页导致的 offset 溢出 / 大偏移查询 DoS。

### SortParam — 排序参数

| 字段          | 类型       | 默认值     | 校验                                                                                                                                 |
|-------------|----------|---------|------------------------------------------------------------------------------------------------------------------------------------|
| `sortField` | `String` | `null`  | `@Pattern(regexp="[a-zA-Z_][a-zA-Z0-9_]{0,62}", message="排序字段必须以字母或下划线开头，仅支持字母、数字和下划线")`, `@Size(max=63, message="排序字段长度不能超过 63")` |
| `sortType`  | `String` | `"asc"` | `@Pattern(regexp="asc\|desc", flags=CASE_INSENSITIVE, message="排序方向必须为 asc 或 desc")` — 不区分大小写                                      |

> `sortField` 正则仅做格式校验（防 SQL 片段注入），**不校验字段是否为真实/非敏感列**。组装 `ORDER BY` 前业务方仍需白名单映射列名，避免按敏感列（如
`password`）排序导致数据推断。注解仅在 Controller 参数标注 `@Valid`/`@Validated` 时生效。

### Page\<T\> — 分页结果

字段均为 `protected`（允许子类访问）：

| 字段          | 类型        | 说明                                       |
|-------------|-----------|------------------------------------------|
| `records`   | `List<T>` | 当前页数据（构造时防御性拷贝并包装为不可修改列表，`getRecords()` 返回不可修改视图） |
| `total`     | `long`    | 总记录数                                     |
| `pageSize`  | `long`    | 每页条数                                     |
| `current`   | `long`    | 当前页码（默认 1）                               |
| `totalPage` | `long`    | 仅由四参构造器计算：`(total + pageSize - 1) / pageSize`（`pageSize <= 0` 时为 0）；两参构造器不计算，恒为 0 |

构造方法：

- `Page(long current, long pageSize, long total, List<T> records)` — 完整构造，自动计算 `totalPage`；`current < 1` 时回退为
  1
- `Page(long total, List<T> records)` — 仅设置 total 与 records，其余元数据为字段默认值（current=1、pageSize=0、totalPage=0，不会按 total 计算总页数）

工厂方法：

- `Page.of(List<T> records, long total, PageParam pageParam)` — 自动从 pageParam 取 current/pageSize
- `Page.of(List<T> records, long total)` — 使用字段默认值（current=1、pageSize=0、totalPage=0），**并非** `PageParam` 的默认分页参数 1/20（内部调用双参构造方法）

### PageSortParam — 组合参数

内嵌 `PageParam`（默认 `new PageParam()`）+ `SortParam`（可选，默认 null），`@Valid` 级联校验。提供 `getOffset()` 委托给内嵌的
`PageParam`。

### Summary — 汇总基类

`total` 字段为 `protected`，业务方继承后可添加聚合字段（如 `totalAmount`、`avgAmount`）。

### PageSummary\<T, S extends Summary\> — 带汇总的分页

继承 `Page<T>`，额外 `summary` 字段（`protected`）。

构造方法：`PageSummary(long current, long pageSize, long total, List<T> records, S summary)`

工厂方法：`PageSummary.of(List<T> records, S summary, PageParam pageParam)`

> **边界条件：** `PageSummary.of()` 内部会读取 `summary.getTotal()` 作为总记录数，`summary` 为 null 时抛出 `NullPointerException`；
> 需要允许空汇总请改用五参构造方法。同理，`Page.of(records, total, pageParam)` 的 `pageParam` 为 null 也会抛出 NPE。

---

## 类关系

```text
PageParam ──组合──► PageSortParam ◄──组合── SortParam
    │                  pageParam=new PageParam()
    │                  sortParam=null (可选)
    │
    │ of()
    ▼
 Page<T> ◄──继承── PageSummary<T, S extends Summary>
  protected fields      └─ summary: S (protected)
```

---

## 依赖

| 依赖                     | 必选/可选 | 作用                                   |
|------------------------|-------|--------------------------------------|
| jakarta.validation-api | 必选    | 参数校验注解（@Min, @Max, @Pattern, @Valid），版本由 BOM 统一为 3.1.1 |
| lombok                 | 必需    | `@Getter` / `@Setter` 简化（编译期注解处理；pom 以 compile scope + `optional=true` 声明，**不会**传递引入下游——下游自身代码使用 Lombok 注解时需自行声明依赖） |
| Bean Validation 实现（如 hibernate-validator / spring-boot-starter-validation） | 校验生效必需 | 仅引入 API 时注解不会被执行，运行时需提供实现并配合 `@Valid`/`@Validated` 触发 |

---

## 扩展点

| 扩展点     | 方式             | 示例                                 |
|---------|----------------|------------------------------------|
| 自定义汇总   | 继承 `Summary`   | `OrderSummary` 添加 `totalAmount` 字段 |
| 多字段排序   | 扩展 `SortParam` | 使用 `List<SortParam>` 支持多排序         |
| 附加查询条件  | 继承 `PageParam` | 添加筛选字段                             |
| 自定义分页结果 | 继承 `Page<T>`   | protected 字段允许子类自由访问               |

---

## 注意事项

- `SortParam` 的正则校验防止 SQL 注入，但建议持久层仍使用白名单映射字段名
- `@Max(100)` 硬编码在 `PageParam` 上，防止大查询导致 OOM
- 与 MyBatis-Plus 的 `Page` 同名，注意导入区分
- `Page` 和 `PageSummary` 的字段为 `protected` 而非 `private`，便于子类扩展
- `PageSortParam` 的 `pageParam` 有默认值，即使不传分页参数也不会 NPE

---

## 版本与兼容性

- 本模块当前版本 `1.0.0`，与 `lightboot-bom` 版本一致，Java 21+；`Spring Boot 4.1.1` 为框架整体基线（本模块不依赖 Spring，
  仅依赖 Jakarta Validation + Lombok）
- `Page` 构造时对 `records` 防御性拷贝且不可修改，该行为自 1.0.0 起固定
- `Page.totalPage` 仅由四参构造器计算，两参构造器恒为 0；`Page.of(records, total)` 同样不计算（元数据为字段默认值，并非
  `PageParam` 的默认 1/20）
- `PageSortParam.sortParam` 未设置时本模块不做任何默认排序，是否应用及默认排序行为由下游消费方决定
- 本模块不依赖 `lightboot-core`；如需配合 `Result` 返回 `Page`，请一并引入 core 模块
