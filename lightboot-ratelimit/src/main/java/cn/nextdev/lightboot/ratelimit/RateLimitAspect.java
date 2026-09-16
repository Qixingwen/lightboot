package cn.nextdev.lightboot.ratelimit;

import cn.nextdev.lightboot.ratelimit.config.RateLimitProperties;
import cn.nextdev.lightboot.ratelimit.exception.RateLimitException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import java.lang.reflect.Method;

/**
 * 限流切面，拦截 {@link RateLimit} 注解方法。
 *
 * <p>解析注解 {@code key}（支持 SpEL 引用方法参数），拼接 {@code rate:} 前缀后调用
 * {@link RateLimitRedisService#allow(String, int, int)} 判断是否放行；超限抛 {@link RateLimitException}。
 * {@code time}/{@code count}/{@code message} 为非正值/空串时回退 {@link RateLimitProperties} 默认值。
 *
 * <p>支持 {@link RateLimits} 重复注解：任一规则超限即拒绝。
 *
 * <p>此切面需 aop 依赖；由 {@link cn.nextdev.lightboot.ratelimit.config.RateLimitAutoConfiguration} 通过 {@code @Bean} + 条件注解注册。
 */
@Aspect
public class RateLimitAspect {

    /**
     * 限流 key 的 Redis 命名空间前缀。
     */
    private static final String KEY_PREFIX = "rate:";

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final RateLimitRedisService rateLimitService;
    private final RateLimitProperties properties;

    /**
     * 创建限流切面。
     *
     * @param rateLimitService 限流计数服务
     * @param properties       限流配置属性（提供默认值）
     */
    public RateLimitAspect(RateLimitRedisService rateLimitService, RateLimitProperties properties) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    /**
     * 环绕通知：对带 {@link RateLimit}（含 {@link RateLimits} 容器）的方法执行限流判断。
     *
     * <p>注解通过反射从目标方法读取，因此本方法既可由 AOP 代理触发，也可在单测中直接调用。
     *
     * @param joinPoint 切点
     * @return 目标方法返回值
     * @throws Throwable 目标方法异常或 {@link RateLimitException}
     */
    @Around("@annotation(cn.nextdev.lightboot.ratelimit.RateLimit) || @annotation(cn.nextdev.lightboot.ratelimit.RateLimits)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RateLimit[] rules = rulesOf(method);
        for (RateLimit rateLimit : rules) {
            check(joinPoint, method, rateLimit);
        }
        return joinPoint.proceed();
    }

    /**
     * 提取方法上的限流规则（兼容单注解与 {@link RateLimits} 容器；
     * 容器存在时优先，此时忽略同方法上单独声明的 {@link RateLimit}）。
     *
     * @param method 目标方法
     * @return 该方法上声明的限流规则数组（无规则时为空数组）
     */
    private RateLimit[] rulesOf(Method method) {
        RateLimits container = method.getAnnotation(RateLimits.class);
        if (container != null) {
            return container.value();
        }
        RateLimit single = method.getAnnotation(RateLimit.class);
        return single != null ? new RateLimit[]{single} : new RateLimit[0];
    }

    /**
     * 单条规则的限流判断：解析 key、回退配置默认值并调用计数服务，超限抛 {@link RateLimitException}。
     *
     * @param joinPoint 切点（用于 SpEL 参数解析）
     * @param method    目标方法
     * @param rateLimit 限流规则注解
     * @throws cn.nextdev.lightboot.ratelimit.exception.RateLimitException 当超过窗口内允许次数时抛出
     */
    private void check(ProceedingJoinPoint joinPoint, Method method, RateLimit rateLimit) {
        String resolvedKey = resolveKey(rateLimit.key(), method, joinPoint.getArgs());
        // 空 key（SpEL 解析为 null/空、或字面量空串）fallback 到方法签名
        // declaringClass#methodName，保证按方法限流而非全局共享同一桶。
        if (resolvedKey == null || resolvedKey.isBlank()) {
            resolvedKey = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        int time = rateLimit.time() > 0 ? rateLimit.time() : properties.getDefaultTime();
        int count = rateLimit.count() > 0 ? rateLimit.count() : properties.getDefaultCount();

        if (!rateLimitService.allow(KEY_PREFIX + resolvedKey, time, count)) {
            String message = (rateLimit.message() == null || rateLimit.message().isEmpty())
                    ? properties.getMessage() : rateLimit.message();
            // 携带限流窗口秒数，供全局异常处理器写入 HTTP 429 的 Retry-After 头
            throw new RateLimitException(message, time);
        }
    }

    /**
     * 解析 SpEL key；非 SpEL 表达式（无 #、无字面量求值需要）直接作为字面量返回。
     *
     * <p>解析与求值包裹 try/catch：非法表达式（如拼写错误的 {@code "#user..id"}）会抛
     * {@link IllegalStateException}（fail loud：异常消息直接指出非法表达式原文，原始 SpEL 异常作为
     * cause，便于日志定位配置错误）。注意这不会改变 HTTP 结果——包装后的异常与透传的 SpEL 异常
     * 经全局异常处理均映射为 HTTP 500，收益仅在异常信息与日志的可读性。
     *
     * @param keyExpr 注解 key 原文
     * @param method  目标方法
     * @param args    方法实参
     * @return 解析后的 key；解析为 null 时返回空串
     * @throws IllegalStateException 当 key 为非法 SpEL 表达式
     */
    private String resolveKey(String keyExpr, Method method, Object[] args) {
        if (keyExpr == null || keyExpr.isEmpty() || keyExpr.indexOf('#') < 0) {
            return keyExpr == null ? "" : keyExpr;
        }
        MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(
                null, method, args, NAME_DISCOVERER);
        try {
            Expression expression = PARSER.parseExpression(keyExpr);
            Object value = expression.getValue(context);
            return value == null ? "" : value.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Invalid @RateLimit key expression: " + keyExpr, e);
        }
    }
}
