package cn.nextdev.lightboot.core.exception;

import cn.nextdev.lightboot.core.api.IErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    /**
     * 构造时传入 null 的 {@link IErrorCode} 不应抛出 NPE，
     * getMessage() 与 getErrorCode() 均应为 null。
     */
    @Test
    void constructor_toleratesNullErrorCode() {
        ApiException ex = new ApiException((IErrorCode) null);
        assertThat(ex.getMessage()).isNull();
        assertThat(ex.getErrorCode()).isNull();
    }

    /**
     * 携带自定义消息的 IErrorCode 构造同样需要容忍 null errorCode。
     */
    @Test
    void constructorWithMessage_toleratesNullErrorCode() {
        ApiException ex = new ApiException(null, "message");
        assertThat(ex.getMessage()).isEqualTo("message");
        assertThat(ex.getErrorCode()).isNull();
    }

    /**
     * 携带自定义消息与原因的 IErrorCode 构造同样需要容忍 null errorCode。
     */
    @Test
    void constructorWithMessageAndCause_toleratesNullErrorCode() {
        Throwable cause = new RuntimeException("boom");
        ApiException ex = new ApiException(null, "message", cause);
        assertThat(ex.getMessage()).isEqualTo("message");
        assertThat(ex.getErrorCode()).isNull();
        assertThat(ex.getCause()).isSameAs(cause);
    }
}
