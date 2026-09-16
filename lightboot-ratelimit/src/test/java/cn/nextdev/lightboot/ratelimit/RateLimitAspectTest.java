package cn.nextdev.lightboot.ratelimit;

import cn.nextdev.lightboot.ratelimit.config.RateLimitProperties;
import cn.nextdev.lightboot.ratelimit.exception.RateLimitException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RateLimitAspect 分支单测（纯 Mockito：直接 new 切面 + mock 服务/JoinPoint/MethodSignature，
 * 注解从测试桩方法反射读取——切面本身按方法反射取注解，无需 AOP 代理即可测，见主类 Javadoc）。
 *
 * <p>源码分支清单 → 用例映射：
 * <ul>
 *   <li>rulesOf：容器注解优先（{@link #containerRules_checkedInOrder} / {@link #repeatedAnnotations_landInContainerPath}）、
 *       单注解包装（{@link #spel_paramReference_resolvesToExpectedKey}）、无注解返回空数组（{@link #noAnnotation_proceedsWithoutCheck}）</li>
 *   <li>SpEL key 参数引用（spel_paramReference…）、属性导航（spel_propertyNavigation…）、
 *       非 SpEL 字面量短路（literalKey…）、非法表达式 fail-loud（illegalSpel…）</li>
 *   <li>空 key 回退：字面量空串（emptyKey…）、SpEL 解析为 null（spelResolvingToNull…），
 *       回退格式 {@code SimpleClassName#methodName}</li>
 *   <li>time/count 非正值回退配置默认值（missingTimeAndCount_fallsBack…）</li>
 *   <li>超限抛 {@link RateLimitException}：自定义消息 + retryAfter + 错误码（reject_throws…）、
 *       空消息回退配置默认消息（reject_withEmptyMessage…）</li>
 *   <li>放行：方法正常执行并透传返回值（pass_through…）</li>
 *   <li>多规则：顺序判定（containerRules_checkedInOrder）、任一超限即拒绝且短路
 *       （containerRules_firstRejects / secondRejects…）</li>
 *   <li>注解作用域：{@link RateLimit} 的 {@code @Target} 仅 METHOD，切面只读方法注解、
 *       无类级回退——无注解方法直接放行即其真实语义（noAnnotation_proceedsWithoutCheck）</li>
 * </ul>
 */
class RateLimitAspectTest {

    private RateLimitRedisService service;
    private RateLimitProperties properties;
    private ProceedingJoinPoint joinPoint;
    private MethodSignature signature;
    private RateLimitAspect aspect;

    @BeforeEach
    void setUp() {
        service = mock(RateLimitRedisService.class);
        properties = new RateLimitProperties();
        properties.setDefaultTime(30);
        properties.setDefaultCount(500);
        properties.setMessage("默认限流提示");

        joinPoint = mock(ProceedingJoinPoint.class);
        signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);

        aspect = new RateLimitAspect(service, properties);
    }

    /**
     * SpEL 引用方法参数：key="#userId" + 实参 42L → allow 收到 "rate:42" 与注解声明的 time/count。
     */
    @Test
    void spel_paramReference_resolvesToExpectedKey() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("byParam", new Class<?>[]{Long.class}, 42L);
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint);

        verify(service).allow("rate:42", 10, 2);
        assertThat(result).isEqualTo("ok");
        verify(joinPoint).proceed();
    }

    /**
     * SpEL 属性导航：key="#user.id" → 取实参对象属性拼 key。
     */
    @Test
    void spel_propertyNavigation_resolvesToExpectedKey() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("byProperty", new Class<?>[]{User.class}, new User(99L));
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint);

        verify(service).allow("rate:99", 10, 2);
    }

    /**
     * 非 SpEL 字面量 key（无 {@code #}）直接短路返回，不走表达式求值。
     */
    @Test
    void literalKey_skipsSpelEvaluation() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("literalKey", new Class<?>[]{});

        aspect.around(joinPoint);

        verify(service).allow("rate:login", 5, 1);
    }

    /**
     * 字面量空串 key 回退为 {@code 声明类简名#方法名}，保证按方法限流而非全局共享同一桶。
     */
    @Test
    void emptyKey_fallsBackToMethodSignature() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("emptyKey", new Class<?>[]{});

        aspect.around(joinPoint);

        verify(service).allow("rate:SampleService#emptyKey", 5, 1);
    }

    /**
     * SpEL 解析为 null（实参本身为 null）→ 空串 → 同样回退到方法签名 key。
     */
    @Test
    void spelResolvingToNull_fallsBackToMethodSignature() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("nullResolvedKey", new Class<?>[]{Object.class}, (Object) null);

        aspect.around(joinPoint);

        verify(service).allow("rate:SampleService#nullResolvedKey", 5, 1);
    }

    /**
     * time/count 未指定（注解默认 -1，非正值）→ 回退 {@link RateLimitProperties} 默认值。
     */
    @Test
    void missingTimeAndCount_fallsBackToPropertiesDefaults() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("usesDefaults", new Class<?>[]{});

        aspect.around(joinPoint);

        verify(service).allow("rate:fallback", 30, 500);
    }

    /**
     * 超限（allow=false）抛 {@link RateLimitException}：携带注解自定义消息、
     * 解析后的窗口秒数（Retry-After）与限流错误码 42901，且目标方法不再执行。
     */
    @Test
    void reject_throwsRateLimitException_withCustomMessageRetryAfterAndCode() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(false);
        stubMethod("customMessage", new Class<?>[]{});

        Throwable thrown = catchThrowable(() -> aspect.around(joinPoint));
        assertThat(thrown).isInstanceOf(RateLimitException.class);
        RateLimitException ex = (RateLimitException) thrown;
        assertThat(ex.getMessage()).isEqualTo("custom-limit");
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(7L);
        assertThat(ex.getErrorCode().getCode()).isEqualTo(42901L);
        verify(joinPoint, never()).proceed();
    }

    /**
     * 超限且注解消息为空串 → 回退配置默认消息（错误码与 retryAfter 语义不变）。
     */
    @Test
    void reject_withEmptyMessage_usesConfiguredDefaultMessage() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(false);
        stubMethod("defaultMessage", new Class<?>[]{});

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(RateLimitException.class)
                .hasMessage("默认限流提示");
    }

    /**
     * 放行（allow=true）时目标方法正常执行，返回值原样透传。
     */
    @Test
    void pass_throughToTargetAndReturnsResult() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("customMessage", new Class<?>[]{});
        when(joinPoint.proceed()).thenReturn("target-result");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("target-result");
        verify(joinPoint).proceed();
    }

    /**
     * 非法 SpEL（拼写错误）fail-loud：抛 {@link IllegalStateException} 且消息包含表达式原文，目标方法不执行。
     */
    @Test
    void illegalSpel_throwsIllegalStateException_withExpressionText() throws Throwable {
        stubMethod("badSpel", new Class<?>[]{});

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("#user..id");
        verify(joinPoint, never()).proceed();
    }

    /**
     * {@link RateLimits} 容器的多条规则依次判定：两条均放行 → 目标方法执行一次。
     */
    @Test
    void containerRules_checkedInOrder() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("explicitContainer", new Class<?>[]{});
        when(joinPoint.proceed()).thenReturn("ok");

        aspect.around(joinPoint);

        inOrder(service).verify(service).allow("rate:ruleA", 1, 1);
        inOrder(service).verify(service).allow("rate:ruleB", 2, 1);
        verify(joinPoint).proceed();
    }

    /**
     * 容器首条规则超限即拒绝并短路：第二条不再判定，目标方法不执行。
     */
    @Test
    void containerRules_firstRejects_shortCircuitsRemainingRules() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(false);
        stubMethod("explicitContainer", new Class<?>[]{});

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(RateLimitException.class);
        verify(service, times(1)).allow(anyString(), anyInt(), anyInt());
        verify(joinPoint, never()).proceed();
    }

    /**
     * 容器首条放行、第二条超限 → 仍拒绝：证明每条规则独立判定而非只看第一条。
     */
    @Test
    void containerRules_secondRejects_afterFirstPasses() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt()))
                .thenReturn(true)
                .thenReturn(false);
        stubMethod("explicitContainer", new Class<?>[]{});

        assertThatThrownBy(() -> aspect.around(joinPoint))
                .isInstanceOf(RateLimitException.class);
        verify(service).allow("rate:ruleA", 1, 1);
        verify(service).allow("rate:ruleB", 2, 1);
        verify(joinPoint, never()).proceed();
    }

    /**
     * 重复标注 {@code @RateLimit}（编译器合成 {@link RateLimits} 容器）走容器路径：两条规则都被判定。
     */
    @Test
    void repeatedAnnotations_landInContainerPath() throws Throwable {
        when(service.allow(anyString(), anyInt(), anyInt())).thenReturn(true);
        stubMethod("repeated", new Class<?>[]{});

        aspect.around(joinPoint);

        verify(service).allow("rate:first", 1, 1);
        verify(service).allow("rate:second", 2, 1);
    }

    /**
     * 方法无限流注解 → 不查 Redis 直接放行（{@link RateLimit} 的 {@code @Target} 仅 METHOD，
     * 切面只读方法注解、不存在类级回退——此即源码的真实作用域语义）。
     */
    @Test
    void noAnnotation_proceedsWithoutCheck() throws Throwable {
        stubMethod("noAnnotation", new Class<?>[]{});
        when(joinPoint.proceed()).thenReturn("plain");

        Object result = aspect.around(joinPoint);

        assertThat(result).isEqualTo("plain");
        verifyNoInteractions(service);
        verify(joinPoint).proceed();
    }

    /**
     * 反射取桩方法并装填 joinPoint/signature 桩（默认实参为空数组）。
     */
    private void stubMethod(String name, Class<?>[] paramTypes, Object... args) throws NoSuchMethodException {
        Method method = SampleService.class.getMethod(name, paramTypes);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
    }

    /**
     * 承载各类注解组合的测试桩（注解由切面反射读取，方法体不会真正执行）。
     */
    static class SampleService {

        @RateLimit(key = "#userId", time = 10, count = 2)
        public String byParam(Long userId) {
            return "ok";
        }

        @RateLimit(key = "#user.id", time = 10, count = 2)
        public String byProperty(User user) {
            return "ok";
        }

        @RateLimit(key = "login", time = 5, count = 1)
        public String literalKey() {
            return "ok";
        }

        @RateLimit(key = "", time = 5, count = 1)
        public String emptyKey() {
            return "ok";
        }

        @RateLimit(key = "#user", time = 5, count = 1)
        public String nullResolvedKey(Object user) {
            return "ok";
        }

        @RateLimit(key = "fallback")
        public String usesDefaults() {
            return "ok";
        }

        @RateLimit(key = "quota", time = 7, count = 3, message = "custom-limit")
        public String customMessage() {
            return "ok";
        }

        @RateLimit(key = "quota", time = 7, count = 3)
        public String defaultMessage() {
            return "ok";
        }

        @RateLimit(key = "#user..id", time = 7, count = 3)
        public String badSpel() {
            return "ok";
        }

        @RateLimits({
                @RateLimit(key = "ruleA", time = 1, count = 1),
                @RateLimit(key = "ruleB", time = 2, count = 1)
        })
        public String explicitContainer() {
            return "ok";
        }

        @RateLimit(key = "first", time = 1, count = 1)
        @RateLimit(key = "second", time = 2, count = 1)
        public String repeated() {
            return "ok";
        }

        public String noAnnotation() {
            return "plain";
        }
    }

    /**
     * SpEL 属性导航用的参数对象。
     */
    static class User {

        private final long id;

        User(long id) {
            this.id = id;
        }

        public long getId() {
            return id;
        }
    }
}
