package cn.nextdev.lightboot.data.pagination;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;

/**
 * 分页查询结果容器。
 *
 * <p>封装当前页的数据记录及分页元数据（总记录数、每页条数、当前页码、总页数），
 * 推荐通过静态工厂方法 {@link #of} 创建实例。
 *
 * <p>{@code records} 在构造时会被防御性拷贝并包装为不可修改列表，外部对原始 list 的
 * 后续修改不会影响本对象，也无法通过 {@code getRecords()} 句柄进行修改。
 *
 * @param <T> 数据记录的类型
 * @see PageParam
 * @see PageSummary
 */
@EqualsAndHashCode
@Getter
@ToString
public class Page<T> {

    /**
     * 当前页的数据记录列表。
     */
    protected List<T> records;

    /**
     * 符合查询条件的总记录数。
     */
    protected long total;

    /**
     * 每页记录数。
     */
    protected long pageSize;

    /**
     * 当前页码，从 1 开始。
     */
    protected long current = 1;

    /**
     * 总页数，由 {@code total} 和 {@code pageSize} 在四参构造器中自动计算；
     * 两参构造器不计算该字段（保持默认值 0）。
     */
    protected long totalPage;

    /**
     * 创建完整的分页结果，自动计算总页数。
     *
     * @param current  当前页码（小于 1 时默认为 1）
     * @param pageSize 每页记录数
     * @param total    总记录数
     * @param records  当前页的数据记录
     */
    public Page(long current, long pageSize, long total, List<T> records) {
        if (current >= 1) {
            this.current = current;
        }
        this.pageSize = pageSize;
        this.total = total;
        this.totalPage = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0;
        this.records = records == null ? Collections.emptyList() : List.copyOf(records);
    }

    /**
     * 创建仅含总记录数和数据记录的分页结果。
     *
     * <p>分页元数据取字段默认值：{@code current}=1、{@code pageSize}=0、{@code totalPage}=0，
     * 不会按 {@code total} 计算总页数——注意这并非 {@link PageParam} 的默认分页参数（第 1 页、
     * 每页 20 条）。需要完整分页元数据时，请使用 {@link #of(List, long, PageParam)}
     * 或四参构造方法 {@link #Page(long, long, long, List)}。
     *
     * @param total   总记录数
     * @param records 数据记录
     */
    public Page(long total, List<T> records) {
        this.total = total;
        this.records = records == null ? Collections.emptyList() : List.copyOf(records);
    }

    /**
     * 根据查询结果和分页参数创建分页结果。
     *
     * @param records   当前页的数据记录
     * @param total     总记录数
     * @param pageParam 分页请求参数
     * @param <T>       数据记录的类型
     * @return 包含完整分页信息的 {@code Page}
     */
    public static <T> Page<T> of(List<T> records, long total, PageParam pageParam) {
        return new Page<>(pageParam.getCurrent(), pageParam.getPageSize(), total, records);
    }

    /**
     * 根据数据记录和总记录数创建分页结果（不使用 {@link PageParam} 的默认分页参数）。
     *
     * @param records 数据记录
     * @param total   总记录数
     * @param <T>     数据记录的类型
     * @return 分页元数据为字段默认值（{@code current}=1、{@code pageSize}=0、{@code totalPage}=0）的 {@code Page}
     */
    public static <T> Page<T> of(List<T> records, long total) {
        return new Page<>(total, records);
    }

}
