package cn.nextdev.lightboot.ratelimit;

import cn.nextdev.lightboot.ratelimit.config.RateLimitProperties;
import cn.nextdev.lightboot.ratelimit.exception.RateLimitException;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解，基于 Redis 滑动窗口算法。
 *
 * <p>{@code key} 支持 SpEL 表达式，可引用方法参数（如 {@code "#ip"}、{@code "#user.id"}），
 * 也可用字面量（如 {@code "login"}）。同一 key 在 {@code time} 秒内最多允许 {@code count} 次请求，
 * 超出抛 {@link RateLimitException}。
 *
 * <p>{@code time}/{@code count}/{@code message} 为非正数或空串时回退到
 * {@link RateLimitProperties} 的默认值。支持重复标注（{@link RateLimits}）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /**
     * 限流 key，支持 SpEL 或字面量。
     *
     * @return 限流 key，支持 SpEL 或字面量
     */
    String key();

    /**
     * 时间窗口（秒），非正数（含默认值 -1）使用配置默认值。
     *
     * @return 时间窗口（秒），非正数使用配置默认值
     */
    int time() default -1;

    /**
     * 窗口内最大次数，非正数（含默认值 -1）使用配置默认值。
     *
     * @return 窗口内最大次数，非正数使用配置默认值
     */
    int count() default -1;

    /**
     * 触发限流时的提示消息，空串使用配置默认值。
     *
     * @return 触发限流时的提示消息，空串使用配置默认值
     */
    String message() default "";
}
