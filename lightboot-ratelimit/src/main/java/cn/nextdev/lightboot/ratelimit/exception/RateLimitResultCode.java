package cn.nextdev.lightboot.ratelimit.exception;

import cn.nextdev.lightboot.core.api.IErrorCode;

/**
 * 限流模块错误码枚举，实现 {@link IErrorCode}。
 */
public enum RateLimitResultCode implements IErrorCode {

    /**
     * 请求过于频繁（触发限流）。
     */
    RATE_LIMIT(42901L, "请求过于频繁，请稍后重试");

    private final long code;
    private final String message;

    RateLimitResultCode(long code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public long getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
