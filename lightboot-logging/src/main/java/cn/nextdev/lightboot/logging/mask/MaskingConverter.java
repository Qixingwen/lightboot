package cn.nextdev.lightboot.logging.mask;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

/**
 * Logback 自定义消息转换器，对日志消息进行脱敏。
 *
 * <p>在 logback-spring.xml 中通过 {@code conversionRule} 注册：
 * <pre>
 * &lt;conversionRule conversionWord="maskMsg" class="cn.nextdev.lightboot.logging.mask.MaskingConverter"/&gt;
 * </pre>
 *
 * <p>然后在 pattern 中使用 {@code %maskMsg} 替代 {@code %msg} 或 {@code %m}。
 *
 * <p>初始化策略：优先从 Spring 容器获取 {@link LogMasker} Bean
 * （支持用户自定义实现），容器未就绪时降级为 {@link DefaultLogMasker}。
 *
 * <p>可通过配置 {@code light-boot.logging.mask.enabled=false} 关闭脱敏：此时转换器成为 no-op
 * （原样返回消息），并使用独立 logger WARN 一次。默认值为 true（默认开启脱敏）。
 * 该开关在 Spring 容器注入（{@code LoggingContextHolder} 就绪）后生效；容器就绪前的
 * 极早期日志仍按默认规则脱敏且不发 WARN。
 */
public class MaskingConverter extends MessageConverter {

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
    public MaskingConverter() {
    }

    private volatile LogMasker logMasker;
    private volatile boolean initialized = false;
    private volatile boolean disabled = false;

    /**
     * 渲染日志消息并经 {@link LogMasker} 脱敏；脱敏被禁用或 masker 未就绪时原样返回。
     */
    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    initMasker();
                }
            }
        }
        // 脱敏被禁用，或容器未就绪且无兜底 masker 时，原样返回（no-op）
        if (disabled || logMasker == null) {
            return message;
        }
        return logMasker.mask(message);
    }

    private void initMasker() {
        try {
            // 通过 LoggingContextHolder 获取 Spring 容器中的自定义 LogMasker Bean
            ApplicationContext ctx = LoggingContextHolder.getApplicationContext();
            // 读 light-boot.logging.mask.enabled，默认 true。显式 false 时关闭脱敏。
            if (ctx != null && Boolean.FALSE.equals(ctx.getEnvironment()
                    .getProperty(MASK_ENABLED_PROPERTY, Boolean.class, Boolean.TRUE))) {
                disabled = true;
                // WARN 的可见次数受 appender 拓扑影响（见 MASK_LOGGER 注释）；DCL 仅保证 initMasker 只执行一次
                MASK_LOGGER.warn("Log masking disabled ({}=false); MaskingConverter is no-op", MASK_ENABLED_PROPERTY);
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
        // 防止极端情况（如 OOM）下 logMasker 为 null 导致所有日志输出 NPE
        if (this.logMasker == null) {
            this.logMasker = new DefaultLogMasker();
        }
        this.initialized = true;
    }
}
