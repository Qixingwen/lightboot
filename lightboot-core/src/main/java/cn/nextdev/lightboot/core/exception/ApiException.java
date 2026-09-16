package cn.nextdev.lightboot.core.exception;

import cn.nextdev.lightboot.core.api.IErrorCode;
import lombok.Getter;

/**
 * 携带结构化 {@link IErrorCode} 的运行时异常。
 *
 * <p>作为框架中 API 层错误的标准异常类型使用。
 * 全局异常处理器可提取 {@link IErrorCode} 以生成统一的
 * {@link cn.nextdev.lightboot.core.api.Result} 响应。
 *
 * @see IErrorCode
 * @see Asserts
 */
@Getter
public class ApiException extends RuntimeException {

    /**
     * 此异常携带的结构化错误码
     */
    private final IErrorCode errorCode;

    /**
     * 使用 {@link IErrorCode} 创建异常。
     *
     * @param errorCode 提供状态码和消息的错误码
     */
    public ApiException(IErrorCode errorCode) {
        super(errorCode == null ? null : errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用自定义消息创建异常，不携带错误码。
     *
     * @param message 详细消息
     */
    public ApiException(String message) {
        super(message);
        this.errorCode = null;
    }

    /**
     * 根据底层原因创建异常。
     *
     * @param cause 底层原因
     */
    public ApiException(Throwable cause) {
        super(cause);
        this.errorCode = null;
    }

    /**
     * 使用错误码和自定义消息创建异常，自定义消息覆盖错误码的默认消息。
     *
     * @param errorCode 提供状态码的错误码
     * @param message   详细消息（覆盖 {@link IErrorCode#getMessage()}）
     */
    public ApiException(IErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用自定义消息和底层原因创建异常。
     *
     * @param message 详细消息
     * @param cause   底层原因
     */
    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = null;
    }

    /**
     * 使用错误码、自定义消息和底层原因创建异常。
     *
     * @param errorCode 提供状态码的错误码
     * @param message   详细消息（覆盖 {@link IErrorCode#getMessage()}）
     * @param cause     底层原因
     */
    public ApiException(IErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 建议客户端重试前等待的秒数，用于 HTTP 429 等响应的 {@code Retry-After} 头。
     *
     * <p>基类默认返回 {@code 0}（未设置），表示不携带重试建议。携带重试语义的子类（如限流异常）
     * 可覆写本方法返回实际窗口秒数；全局异常处理器在 {@code > 0} 时写入 {@code Retry-After} 头。
     * 将该钩子放在基类可避免 web 模块反向依赖具体业务异常类型（如 ratelimit 模块）。
     *
     * @return 建议重试等待秒数；{@code 0} 表示未设置
     */
    public long getRetryAfterSeconds() {
        return 0L;
    }

}
