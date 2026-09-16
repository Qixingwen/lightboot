package cn.nextdev.lightboot.data.pagination;

import cn.nextdev.lightboot.data.sort.SortParam;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;

/**
 * 组合分页与排序的请求参数。
 *
 * <p>同时携带 {@link PageParam} 和 {@link SortParam}，适用于需要分页 + 排序的查询接口。
 * 嵌套对象通过 {@code @Valid} 触发级联校验。
 *
 * @see PageParam
 * @see SortParam
 */
@Getter
@Setter
public class PageSortParam {

    /**
     * 默认构造方法。
     */
    public PageSortParam() {
    }

    /**
     * 分页参数，默认初始化。
     */
    @Valid
    private PageParam pageParam = new PageParam();

    /**
     * 排序参数，可选。本类不消费该字段，是否应用以及未设置时的默认排序行为由下游消费方自行决定。
     */
    @Valid
    private SortParam sortParam;

    /**
     * 获取分页偏移量，委托给内部 {@link PageParam}。
     *
     * @return 零基偏移量
     * @see PageParam#getOffset()
     */
    public long getOffset() {
        return pageParam.getOffset();
    }

}
