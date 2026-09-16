package cn.nextdev.lightboot.data.pagination;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTest {

    /**
     * 传入的 list 被防御性拷贝：外部后续修改不应影响 Page.records。
     */
    @Test
    void records_areDefensivelyCopied() {
        List<String> original = new ArrayList<>(Arrays.asList("a", "b"));
        Page<String> page = new Page<>(1, 10, 2, original);

        // 外部修改原始 list
        original.add("c");

        // Page 持有的 records 不应包含新增元素（拷贝隔离）
        assertThat(page.getRecords()).doesNotContain("c").containsExactly("a", "b");

        // getRecords() 返回的视图不可修改
        assertThatThrownBy(() -> page.getRecords().add("d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * 传入 null records 时应得到空列表而非 null。
     */
    @Test
    void records_nullBecomesEmptyList() {
        Page<Object> page = new Page<>(1, 10, 0, null);
        assertThat(page.getRecords()).isNotNull().isEmpty();
        assertThat(page.getRecords()).isSameAs(Collections.emptyList());
    }

    /**
     * 字段相同的两个 Page 应相等且 hashCode 一致。
     */
    @Test
    void equals_andHashCode_matchForEqualPages() {
        List<String> records1 = Arrays.asList("x", "y");
        List<String> records2 = new ArrayList<>(records1);
        Page<String> p1 = new Page<>(2, 20, 40, records1);
        Page<String> p2 = new Page<>(2, 20, 40, records2);

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());

        // 不同字段应不相等
        Page<String> p3 = new Page<>(2, 20, 41, records1);
        assertThat(p1).isNotEqualTo(p3);
    }

    /**
     * toString 不应是默认的 identity 形式，应包含类名或字段信息。
     */
    @Test
    void toString_isNotDefault() {
        Page<String> page = new Page<>(1, 10, 5, Arrays.asList("a", "b"));
        String s = page.toString();
        assertThat(s).contains("Page");
        // 不应包含默认 identity 的 @ 哈希码形式
        assertThat(s).doesNotContain(Page.class.getName() + "@");
    }
}
