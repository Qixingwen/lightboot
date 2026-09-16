package cn.nextdev.lightboot.ratelimit.config;

import cn.nextdev.lightboot.ratelimit.RateLimitAspect;
import cn.nextdev.lightboot.ratelimit.RateLimitRedisService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.util.Assert;

/**
 * 限流自动配置。
 *
 * <p><b>门控条件（按 Bean 分层）：</b>
 * <ul>
 *   <li>类级：{@code light-boot.ratelimit.enabled}（默认 true）为业务开关；
 *       类路径需存在 {@link RedisTemplate}（即引入了 spring-data-redis）</li>
 *   <li>{@code rateLimitRedisService}：另需容器中存在 {@link RedisTemplate} Bean（即引入方已启用 redis）</li>
 *   <li>{@code rateLimitAspect}：另需 {@link cn.nextdev.lightboot.ratelimit.RateLimitRedisService} Bean 就绪
 *       且类路径存在 AOP——缺少 AOP 依赖时仅装配 service 而无切面，注解不会生效（降级为不限流，不报错）</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnClass({RedisTemplate.class, RateLimitAspect.class})
@ConditionalOnProperty(prefix = "light-boot.ratelimit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    /**
     * 校验限流配置，非法配置启动即失败（fail-fast）。
     *
     * <p>{@code default-time} 会作为 {@code RateLimitException} 的 {@code retryAfterSeconds}
     * 传递（契约要求 {@code > 0}，否则 web 层的 {@code Retry-After} 头不会写入），
     * {@code default-count} 是默认窗口内的最大允许次数，两者必须为正数。
     *
     * @param properties 限流配置属性
     * @throws IllegalArgumentException {@code default-time} 或 {@code default-count} 非正数
     */
    public RateLimitAutoConfiguration(RateLimitProperties properties) {
        Assert.isTrue(properties.getDefaultTime() > 0,
                "light-boot.ratelimit.default-time 必须大于 0，当前值：" + properties.getDefaultTime());
        Assert.isTrue(properties.getDefaultCount() > 0,
                "light-boot.ratelimit.default-count 必须大于 0，当前值：" + properties.getDefaultCount());
    }

    /**
     * 限流计数服务（需要容器中有 RedisTemplate）。
     *
     * @param redisTemplate Redis 操作模板
     * @param properties    限流配置属性（提供 failOpen 决策）
     * @return RateLimitRedisService 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedisTemplate.class)
    public RateLimitRedisService rateLimitRedisService(RedisTemplate<String, Object> redisTemplate, RateLimitProperties properties) {
        return new RateLimitRedisService(redisTemplate, properties.isFailOpen());
    }

    /**
     * 限流切面（需要 RateLimitRedisService 就绪 + AOP 依赖）。
     *
     * @param rateLimitService 限流计数服务
     * @param properties       限流配置属性
     * @return RateLimitAspect 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RateLimitRedisService.class)
    @ConditionalOnClass(name = "org.aspectj.lang.ProceedingJoinPoint")
    public RateLimitAspect rateLimitAspect(RateLimitRedisService rateLimitService, RateLimitProperties properties) {
        return new RateLimitAspect(rateLimitService, properties);
    }
}
