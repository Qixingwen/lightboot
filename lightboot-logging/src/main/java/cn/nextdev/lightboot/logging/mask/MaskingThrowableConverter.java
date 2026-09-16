package cn.nextdev.lightboot.logging.mask;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Logback 自定义异常（堆栈）转换器，对异常信息与堆栈进行脱敏。
 *
 * <p>背景：Logback 的 {@code %msg}/{@code %maskMsg} 只处理格式化后的消息，异常（消息体 + 堆栈 +
 * {@code Caused by} 链）由<b>独立</b>的 {@code %ex}/{@code %throwable}（{@link ExtendedThrowableProxyConverter}
 * 等）渲染，<b>不经过</b> {@link MaskingConverter}。因此 {@code log.error("x", e)} 若
 * {@code e.getMessage()} 或栈帧含 {@code password=...} 会明文落盘。本转换器在 {@code %maskEx} 位置
 * 拦截已渲染的异常文本并交给 {@link LogMasker} 脱敏。
 *
 * <p>在 {@code logback-spring.xml} 中注册：
 * <pre>
 * &lt;conversionRule conversionWord="maskEx" class="cn.nextdev.lightboot.logging.mask.MaskingThrowableConverter"/&gt;
 * </pre>
 * 然后在 pattern 中用 {@code %maskEx} 替代默认的 {@code %ex}/{@code %throwable}。
 *
 * <p>初始化策略与 {@link MaskingConverter} 完全一致：优先从 Spring 容器获取 {@link LogMasker} Bean，
 * 容器未就绪时降级为 {@link DefaultLogMasker}；{@code light-boot.logging.mask.enabled=false} 时成为 no-op
 * （该开关在容器注入后生效，容器就绪前的极早期日志仍按默认规则脱敏）。
 *
 * <p>无异常的事件 {@code super.convert} 返回空串，此时原样返回（不进脱敏、不产生多余输出）。
 */
public class MaskingThrowableConverter extends ExtendedThrowableProxyConverter {

    /**
     * 独立 logger，用于输出「脱敏已禁用」告警。
     *
     * <p>使用命名 logger {@code "light-boot.logging.mask"} 以便在 logback 中单独管理级别。
     * 防重入实际由 logback Appender 的线程级跟踪保证：该 WARN 在转换器渲染期间发出，
     * 经同一 appender 再入时会被丢弃。因此告警的可见次数取决于 appender 拓扑——
     * 单 appender 时可能完全不可见，多 appender 时可能重复输出多次，并非「恰好一次」。
     */
    private static final Logger MASK_LOGGER = LoggerFactory.getLogger("light-boot.logging.mask");

    /**
     * 脱敏开关属性名。默认值 true（默认开启）。
     */
    private static final String MASK_ENABLED_PROPERTY = "light-boot.logging.mask.enabled";

    /**
     * 默认构造方法。
     */
    public MaskingThrowableConverter() {
    }

    private volatile LogMasker logMasker;
    private volatile boolean initialized = false;
    private volatile boolean disabled = false;

    /**
     * 渲染事件携带的异常（消息体 + 堆栈 + {@code Caused by} 链），并经 {@link LogMasker} 脱敏后返回。
     *
     * <p>首次调用时按需初始化 masker（双重检查锁）；脱敏被禁用、masker 缺失、或事件无异常
     * （{@code super.convert} 返回空串）时原样返回，保证 no-op 语义且不产生多余输出。
     *
     * @param event Logback 日志事件
     * @return 脱敏后的异常文本；事件无异常时返回空串
     */
    @Override
    public String convert(ILoggingEvent event) {
        String rendered = super.convert(event);
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    initMasker();
                }
            }
        }
        // 无异常时 super.convert 返回空串；脱敏被禁用/无 masker/空文本时原样返回（no-op）
        if (disabled || logMasker == null || rendered == null || rendered.isEmpty()) {
            return rendered;
        }
        return logMasker.mask(rendered);
    }

    private void initMasker() {
        try {
            // 通过 LoggingContextHolder 获取 Spring 容器中的自定义 LogMasker Bean
            ApplicationContext ctx = LoggingContextHolder.getApplicationContext();
            // 读 light-boot.logging.mask.enabled，默认 true。显式 false 时关闭脱敏。
            // 与 MaskingConverter 一致使用 Boolean.FALSE.equals(...) 做 null 安全判定，
            // 避免对返回值自动拆箱（即便 getProperty 带默认值，保持写法健壮一致）。
            if (ctx != null && Boolean.FALSE.equals(ctx.getEnvironment()
                    .getProperty(MASK_ENABLED_PROPERTY, Boolean.class, Boolean.TRUE))) {
                disabled = true;
                MASK_LOGGER.warn("Log masking disabled ({}=false); MaskingThrowableConverter is no-op", MASK_ENABLED_PROPERTY);
                logMasker = null;
                initialized = true;
                return;
            }
            if (ctx != null) {
                this.logMasker = ctx.getBean(LogMasker.class);
            }
        } catch (Exception e) {
            // 容器未就绪或无对应 Bean，降级为默认实现
        }
        // 兜底：无论上面是否成功赋值，都要保证 logMasker 不为 null
        if (this.logMasker == null) {
            this.logMasker = new DefaultLogMasker();
        }
        this.initialized = true;
    }
}
