package cn.nextdev.lightboot.data.pagination;

import lombok.Getter;

import java.util.List;

/**
 * 携带汇总数据的分页结果。
 *
 * <p>在 {@link Page} 基础上扩展了 {@link Summary} 载荷，支持在返回分页记录的同时
 * 附带聚合指标（合计金额、平均值等），一次请求获取完整信息。
 *
 * @param <T> 数据记录的类型
 * @param <S> 汇总数据的具体类型，须继承 {@link Summary}
 * @see Page
 * @see Summary
 */
@Getter
public class PageSummary<T, S extends Summary> extends Page<T> {

    /**
     * 全量结果集的聚合汇总数据。
     */
    protected S summary;

    /**
     * 创建包含汇总数据的完整分页结果。
     *
     * @param current  当前页码，从 1 开始
     * @param pageSize 每页记录数
     * @param total    总记录数
     * @param records  当前页的数据记录
     * @param summary  聚合汇总数据
     */
    public PageSummary(long current, long pageSize, long total, List<T> records, S summary) {
        super(current, pageSize, total, records);
        this.summary = summary;
    }

    /**
     * 根据查询结果、汇总数据和分页参数创建分页结果。
     *
     * <p>总记录数从 {@link Summary#total} 获取。
     *
     * @param records   当前页的数据记录
     * @param summary   聚合汇总数据（同时提供总记录数）
     * @param pageParam 分页请求参数
     * @param <T>       数据记录的类型
     * @param <S>       汇总数据的具体类型
     * @return 包含完整分页信息和汇总数据的 {@code PageSummary}
     */
    public static <T, S extends Summary> PageSummary<T, S> of(List<T> records, S summary, PageParam pageParam) {
        return new PageSummary<>(pageParam.getCurrent(), pageParam.getPageSize(), summary.getTotal(), records, summary);
    }

}
