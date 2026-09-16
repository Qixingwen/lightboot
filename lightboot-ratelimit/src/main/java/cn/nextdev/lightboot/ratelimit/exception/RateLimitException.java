package cn.nextdev.lightboot.ratelimit.exception;

import cn.nextdev.lightboot.core.exception.ApiException;

/**
 * 触发限流时抛出的业务异常。
 *
 * <p>携带 {@link RateLimitResultCode#RATE_LIMIT} 错误码（42901），继承 {@link cn.nextdev.lightboot.core.exception.ApiException}，
 * 由 web 模块的 {@code GlobalExceptionHandler} 作为 {@code ApiException} 统一捕获，
 * 经 {@code HttpStatusCodeResolver} 映射为 HTTP 429（{@code Result.code=42901}）。
 *
 * <p>同时携带 {@code retryAfterSeconds}（限流窗口秒数），由全局异常处理器写入 HTTP 429 响应的
 * {@code Retry-After} 头，告知客户端等待多久后重试。默认 1 秒，与限流默认窗口
 * （{@code RateLimitProperties.defaultTime=1}）一致。
 */
public class RateLimitException extends ApiException {

    /**
     * 默认重试等待秒数（与限流默认窗口一致）。
     */
    private static final long DEFAULT_RETRY_AFTER_SECONDS = 1L;

    /**
     * 建议客户端重试前等待的秒数，对应 HTTP {@code Retry-After} 头。
     */
    private final long retryAfterSeconds;

    /**
     * 使用默认限流错误码、默认消息与默认窗口（1 秒）创建异常。
     */
    public RateLimitException() {
        this(RateLimitResultCode.RATE_LIMIT.getMessage(), DEFAULT_RETRY_AFTER_SECONDS);
    }

    /**
     * 使用自定义消息与默认窗口（1 秒）创建异常（错误码仍为 RATE_LIMIT）。
     *
     * @param message 自定义提示消息
     */
    public RateLimitException(String message) {
        this(message, DEFAULT_RETRY_AFTER_SECONDS);
    }

    /**
     * 使用自定义消息与限流窗口秒数创建异常。
     *
     * <p>窗口秒数由 {@code RateLimitAspect} 解析注解 {@code time}（回退默认值）后传入，
     * 用于生成 {@code Retry-After} 头。
     *
     * @param message           自定义提示消息
     * @param retryAfterSeconds 限流窗口秒数（&gt; 0）
     */
    public RateLimitException(String message, long retryAfterSeconds) {
        super(RateLimitResultCode.RATE_LIMIT, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /**
     * 返回建议客户端重试前等待的秒数，用于 HTTP 429 响应的 {@code Retry-After} 头。
     *
     * @return 重试等待秒数
     */
    @Override
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
