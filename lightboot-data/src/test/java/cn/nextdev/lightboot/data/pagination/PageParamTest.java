package cn.nextdev.lightboot.data.pagination;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageParamTest {

    /**
     * 超大 current 不应导致 offset 溢出为负数（深分页 DoS）。
     */
    @Test
    void getOffset_doesNotOverflowOnHugeCurrent() {
        PageParam p = new PageParam();
        p.setCurrent(Long.MAX_VALUE);
        p.setPageSize(100);
        // 超大 current 不应产生负数 offset（溢出）
        assertThat(p.getOffset()).isNotNegative();
    }

    /**
     * 正常分页 offset 计算应保持正确：(current-1)*pageSize。
     */
    @Test
    void getOffset_normalComputation() {
        PageParam p = new PageParam();
        p.setCurrent(3);
        p.setPageSize(20);
        assertThat(p.getOffset()).isEqualTo(40L);
    }
}
