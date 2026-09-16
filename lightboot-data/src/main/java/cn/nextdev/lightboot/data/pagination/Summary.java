package cn.nextdev.lightboot.data.pagination;

import lombok.Getter;
import lombok.Setter;

/**
 * 分页响应中聚合汇总数据的基类。
 *
 * <p>继承此类添加适用于全量结果集的聚合字段（合计金额、平均值等），
 * 配合 {@link PageSummary} 一次性返回分页数据和汇总信息。
 *
 * @see PageSummary
 */
@Getter
@Setter
public class Summary {

    /**
     * 默认构造方法。
     */
    public Summary() {
    }

    /**
     * 全量结果集的总记录数。
     */
    protected long total;

}
