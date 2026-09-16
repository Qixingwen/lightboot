package cn.nextdev.lightboot.redis.exception;

import cn.nextdev.lightboot.core.api.ResultCode;
import cn.nextdev.lightboot.core.exception.ApiException;
import lombok.Getter;

import java.time.LocalDate;

/**
 * 序列号生成失败时抛出的专用异常。
 *
 * <p>继承 {@link ApiException}，携带 {@link ResultCode#INTERNAL_ERROR} 错误码，
 * 因此会被全局异常处理器的通用 {@code ApiException} 处理器自动捕获，映射为 HTTP 500。
 * 同时保留业务前缀与日期上下文，便于定位问题；保留原始异常链（cause）。
 */
@Getter
public class SequenceGenerationException extends ApiException {

    /**
     * 业务前缀，用于定位序列号生成失败的范围。
     */
    private final String prefix;

    /**
     * 目标日期，序列号生成所针对的日期。
     */
    private final LocalDate date;

    /**
     * 创建序列号生成异常。
     *
     * @param prefix  业务前缀
     * @param date    目标日期
     * @param message 异常消息
     */
    public SequenceGenerationException(String prefix, LocalDate date, String message) {
        super(ResultCode.INTERNAL_ERROR, message);
        this.prefix = prefix;
        this.date = date;
    }

    /**
     * 创建序列号生成异常（带原因）。
     *
     * @param prefix  业务前缀
     * @param date    目标日期
     * @param message 异常消息
     * @param cause   原始异常
     */
    public SequenceGenerationException(String prefix, LocalDate date, String message, Throwable cause) {
        super(ResultCode.INTERNAL_ERROR, message, cause);
        this.prefix = prefix;
        this.date = date;
    }

}
