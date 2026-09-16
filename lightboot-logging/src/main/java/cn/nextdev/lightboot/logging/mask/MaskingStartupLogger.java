package cn.nextdev.lightboot.logging.mask;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 启动时一次性告警：提醒运维日志脱敏<b>默认不生效</b>，需在 {@code logback-spring.xml} 手动接线。
 *
 * <p>背景：本框架的脱敏通过 Logback 转换词 {@code %maskMsg}（消息，{@link MaskingConverter}）与
 * {@code %maskEx}（异常堆栈，{@link MaskingThrowableConverter}）实现，但框架<b>不从库内附带
 * {@code logback-spring.xml}</b>（库带 logback 配置是反模式：会被使用方配置覆盖致假安全，或反过来
 * 覆盖使用方日志拓扑）。因此使用方若沿用 Spring Boot 默认的 {@code %msg/%ex} pattern，
 * 应用日志与异常堆栈<b>不会被脱敏</b>——尽管 {@code light-boot.logging.mask.enabled} 默认 {@code true}，
 * 容易让人误以为开箱即用。
 *
 * <p>本监听器在 {@link ApplicationReadyEvent} 时发一条 WARN，给出确切的注册片段，把这一隐性前提显性化。
 * 当前唯一自动脱敏的是 {@link cn.nextdev.lightboot.logging.request.RequestLogFilter} 的请求查询串
 * （在代码里主动调用 {@link LogMasker}，不依赖 logback layout）。
 *
 * <p>使用方接线并希望消音时，有以下选择：
 * <ul>
 *   <li>置 {@code light-boot.logging.mask.wired=true}：向框架声明「脱敏转换器已正确接线」，本监听器即静默
 *       （推荐——显式声明，无运行时误判风险）。</li>
 *   <li>注册自己的同名 Bean 覆盖（{@code @ConditionalOnMissingBean}）。</li>
 *   <li>置 {@code light-boot.logging.mask.enabled=false}（同时也关闭脱敏本身）。</li>
 * </ul>
 * 注意：框架不检测 Logback 运行时 layout，是否真的使用了 {@code %maskMsg}/{@code %maskEx} 由使用方自行保证。
 */
public class MaskingStartupLogger implements ApplicationListener<ApplicationReadyEvent> {

    /**
     * 独立 logger，命名 {@code "light-boot.logging.mask"}，便于在 logback 中单独管理级别。
     * 本告警在应用就绪（脱敏转换器已完成初始化）后发出，经含 {@code %maskMsg}/{@code %maskEx}
     * 的 layout 渲染时不再参与初始化路径。
     */
    private static final Logger LOG = LoggerFactory.getLogger("light-boot.logging.mask");

    /**
     * 注册片段。
     */
    private static final String SNIPPET = """
            To enable log masking, register in your logback-spring.xml:
              <conversionRule conversionWord="maskMsg" class="cn.nextdev.lightboot.logging.mask.MaskingConverter"/>
              <conversionRule conversionWord="maskEx"  class="cn.nextdev.lightboot.logging.mask.MaskingThrowableConverter"/>
            and use %maskMsg / %maskEx in your pattern, e.g. %d %-5level %logger - %maskMsg %maskEx%n""";

    /**
     * 脱敏属性前缀，用于拼接 {@code .wired} 等子属性。
     */
    private static final String MASK_PREFIX = "light-boot.logging.mask";

    private final AtomicBoolean alreadyLogged = new AtomicBoolean();

    /**
     * 默认构造方法。
     */
    public MaskingStartupLogger() {
    }

    /**
     * 应用就绪时输出一次脱敏接线告警。
     *
     * <p>通过 {@link AtomicBoolean#compareAndSet(boolean, boolean)} 保证同一实例只告警一次，
     * 抵御 {@link ApplicationReadyEvent} 在上下文刷新等场景下的重复触发。
     *
     * <p>若使用方已置 {@code light-boot.logging.mask.wired=true} 声明完成接线，则静默不告警。
     *
     * @param event 应用就绪事件，用于读取 {@code light-boot.logging.mask.wired} 属性
     */
    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        if (!alreadyLogged.compareAndSet(false, true)) {
            return;
        }
        if (isMaskingWired(event)) {
            return;
        }
        LOG.warn("Log masking is NOT active by default. Only HTTP request query strings are auto-masked "
                + "(via RequestLogFilter); application log messages and exception stack traces are NOT masked "
                + "until you wire the Logback conversion rules. {}", SNIPPET);
    }

    /**
     * 判断使用方是否已声明完成 {@code %maskMsg}/{@code %maskEx} 接线。
     *
     * <p>读取 {@code light-boot.logging.mask.wired}（默认 {@code false}）。环境不可读时按未声明处理（仍告警）。
     *
     * @return 使用方已声明接线（{@code wired=true}）返回 {@code true}；否则 {@code false}
     */
    private boolean isMaskingWired(ApplicationReadyEvent event) {
        try {
            return event.getApplicationContext()
                    .getEnvironment()
                    .getProperty(MASK_PREFIX + ".wired", Boolean.class, Boolean.FALSE);
        } catch (Exception e) {
            return false;
        }
    }
}
