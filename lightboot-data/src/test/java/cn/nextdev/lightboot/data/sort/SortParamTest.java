package cn.nextdev.lightboot.data.sort;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SortParamTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    /**
     * 合法字段名（字母开头）应通过校验。
     */
    @Test
    void validSortField_passesValidation() {
        SortParam param = new SortParam();
        param.setSortField("userName");
        param.setSortType("asc");
        Set<ConstraintViolation<SortParam>> violations = validator.validate(param);
        assertThat(violations).isEmpty();
    }

    /**
     * 下划线开头的字段名应通过校验。
     */
    @Test
    void underscorePrefixedSortField_passesValidation() {
        SortParam param = new SortParam();
        param.setSortField("_created_at");
        Set<ConstraintViolation<SortParam>> violations = validator.validate(param);
        assertThat(violations).isEmpty();
    }

    /**
     * 数字开头的字段名应被正则拒绝（防止注入与误用）。
     */
    @Test
    void leadingDigitSortField_failsValidation() {
        SortParam param = new SortParam();
        param.setSortField("1abc");
        Set<ConstraintViolation<SortParam>> violations = validator.validate(param);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sortField"));
    }

    /**
     * 超长字段名（> 63）应被 @Size 拒绝。
     */
    @Test
    void overlongSortField_failsValidation() {
        SortParam param = new SortParam();
        param.setSortField("a" + "b".repeat(63)); // 64 chars
        Set<ConstraintViolation<SortParam>> violations = validator.validate(param);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sortField"));
    }

    // ==================== sortType（排序方向） ====================

    /**
     * 默认构造后 sortType 应为 "asc"，避免客户端未传时下游 ORDER BY 方向未定义 / NPE。
     */
    @Test
    void defaultSortType_isAsc() {
        assertThat(new SortParam().getSortType()).isEqualTo("asc");
    }

    /**
     * 大写 DESC 应通过（@Pattern 大小写不敏感）。
     */
    @Test
    void uppercaseSortType_passesValidation() {
        SortParam param = new SortParam();
        param.setSortField("userName");
        param.setSortType("DESC");
        assertThat(validator.validate(param)).isEmpty();
    }

    /**
     * 非法方向值应被 @Pattern 拒绝。
     */
    @Test
    void invalidSortType_failsValidation() {
        SortParam param = new SortParam();
        param.setSortField("userName");
        param.setSortType("random");
        Set<ConstraintViolation<SortParam>> violations = validator.validate(param);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sortType"));
    }

    /**
     * 显式 null 的 sortType 应通过校验（保持可选语义）。
     */
    @Test
    void nullSortType_passesValidation() {
        SortParam param = new SortParam();
        param.setSortField("userName");
        param.setSortType(null);
        assertThat(validator.validate(param)).isEmpty();
    }
}
