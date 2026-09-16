package cn.nextdev.lightboot.data.pagination;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 分页请求参数，内置校验约束。
 *
 * <p>绑定到查询参数或请求体使用，调用方可省略部分参数，页码默认为第 1 页，每页 20 条。
 *
 * @see Page
 * @see PageSortParam
 */
@Getter
@Setter
public class PageParam {

    /**
     * 默认页码，从 1 开始。
     */
    private static final long DEFAULT_CURRENT = 1;

    /**
     * 默认每页记录数。
     */
    private static final long DEFAULT_PAGE_SIZE = 20;

    /**
     * 默认构造方法。
     */
    public PageParam() {
    }

    /**
     * 当前页码，从 1 开始，上限 1,000,000 以防止深分页导致的 offset 溢出与 DoS。
     */
    @Min(value = 1, message = "页码最小值为 1")
    @Max(value = 1_000_000L, message = "页码超出允许范围")
    private long current = DEFAULT_CURRENT;

    /**
     * 每页记录数，取值范围 1 ~ 100。
     */
    @Min(value = 1, message = "每页条数最小值为 1")
    @Max(value = 100, message = "每页条数最大值为 100")
    private long pageSize = DEFAULT_PAGE_SIZE;

    /**
     * 计算数据库查询使用的零基偏移量。
     *
     * <p>基础公式为 {@code (current - 1) * pageSize}；为防止超大 current 导致乘法
     * 溢出为负数（深分页 DoS），结果会夹紧到 {@link Long#MAX_VALUE}，绝不会返回负数。
     *
     * @return 偏移量，非负；溢出时夹紧为 {@link Long#MAX_VALUE}
     */
    public long getOffset() {
        long c = getCurrent() - 1;
        long size = getPageSize();
        if (c <= 0 || size <= 0) {
            return 0;
        }
        if (c > Long.MAX_VALUE / size) {
            return Long.MAX_VALUE;
        }
        return c * size;
    }

}
