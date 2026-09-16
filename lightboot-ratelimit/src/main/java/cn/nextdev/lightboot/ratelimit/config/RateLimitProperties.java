package cn.nextdev.lightboot.ratelimit.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流配置属性（前缀：{@code light-boot.ratelimit}）。
 *
 * <p>引入方具备 redis 依赖时限流组件即装配（service 装配仅依赖 redis）；限流<b>生效</b>还需 aop 依赖
 * ——无 aop 时仅装配 RedisService 而无切面（见 {@code RateLimitAutoConfiguration} 的分层条件）。
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "light-boot.ratelimit")
public class RateLimitProperties {

    /**
     * 默认构造方法。
     */
    public RateLimitProperties() {
    }

    /**
     * 是否启用限流。默认 true（但需 redis+aop 依赖到位才会真正装配）。
     */
    private boolean enabled = true;

    /**
     * 默认时间窗口（秒），必须为正数（作为 {@code RateLimitException} 的
     * {@code retryAfterSeconds} 传递，非正数时 {@code Retry-After} 头不会写入）。
     * 非法值在启动阶段由 {@link RateLimitAutoConfiguration} 校验并 fail-fast。
     */
    private int defaultTime = 1;

    /**
     * 默认窗口内最大允许次数，必须为正数。
     * 非法值在启动阶段由 {@link RateLimitAutoConfiguration} 校验并 fail-fast。
     */
    private int defaultCount = 100;

    /**
     * Redis 异常时是否放行（fail-open）。
     *
     * <p>true（默认）：Redis 不可用时放行请求，避免缓存层抖动阻断业务；
     * false：Redis 不可用时拒绝请求（安全优先，适合防滥用场景）。
     */
    private boolean failOpen = true;

    /**
     * 默认限流提示消息。
     */
    private String message = "请求过于频繁，请稍后重试";
}
