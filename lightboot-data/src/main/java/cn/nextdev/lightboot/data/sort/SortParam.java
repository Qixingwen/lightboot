package cn.nextdev.lightboot.data.sort;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 数据查询排序参数，承载排序字段名与排序方向。
 *
 * <p>通常与 {@link cn.nextdev.lightboot.data.pagination.PageSortParam} 组合使用，作为分页排序请求的绑定对象。
 * 两个字段均带 Bean Validation 注解，<b>仅在控制器形参标注 {@code @Valid}/{@code @Validated} 时才生效</b>；
 * 框架不强制校验，消费方需自行确保触发。
 *
 * <p><b>安全边界（重要）</b>：本类只做<b>格式</b>校验（字段名为合法标识符、方向为 asc/desc），
 * 用于阻断 SQL 片段注入；<b>不</b>校验字段名是否为真实/敏感列名。即校验通过的 {@code sortField}
 * 仍可能是 {@code password}、{@code id_card} 等敏感列，被拼入 {@code ORDER BY} 后可被用于数据推断。
 * 因此消费方在拼装 SQL 前，必须对 {@link #sortField} 做<b>列名白名单</b>兜底校验。
 *
 * @see cn.nextdev.lightboot.data.pagination.PageSortParam
 */
@Getter
@Setter
public class SortParam {

    /**
     * 默认构造方法。
     */
    public SortParam() {
    }

    /**
     * 排序字段名称，必须以字母或下划线开头，长度 1 ~ 63，防止数字开头或注入。
     *
     * <p><b>使用约束（重要）</b>：此处的 {@code @Pattern}/{@code @Size} 仅做<b>标识符形态</b>校验，
     * 防止 SQL 片段注入，但<b>不校验列名是否合法/敏感</b>。消费方必须：
     * <ul>
     *   <li>在控制器形参上标注 {@code @Valid}（或 {@code @Validated}）触发 Bean Validation——
     *       否则这些注解不会生效，任意字符串会被原样带到数据层。</li>
     *   <li>在拼装 {@code ORDER BY} 前，对 {@code sortField} 做<b>列名白名单</b>校验，
     *       避免 {@code ORDER BY password} 这类按敏感列排序导致数据推断。</li>
     * </ul>
     */
    @Pattern(regexp = "[a-zA-Z_][a-zA-Z0-9_]{0,62}", message = "排序字段必须以字母或下划线开头，仅支持字母、数字和下划线")
    @Size(max = 63, message = "排序字段长度不能超过 63")
    private String sortField;

    /**
     * 排序方向，{@code asc}（升序）或 {@code desc}（降序），不区分大小写。
     *
     * <p>默认 {@code "asc"}，避免客户端未传时 {@code sortType} 为 {@code null} 导致下游
     * {@code ORDER BY} 方向未定义或 NPE。允许显式传 {@code null}（保持可选语义），但默认值
     * 已消除「未传 → null」这一隐患路径。
     */
    @Pattern(regexp = "asc|desc", flags = Pattern.Flag.CASE_INSENSITIVE, message = "排序方向必须为 asc 或 desc")
    private String sortType = "asc";

}
