package cn.nextdev.lightboot.logging.mask;

import org.springframework.context.ApplicationContext;

/**
 * Spring ApplicationContext 静态持有器。
 *
 * <p>供非 Spring 管理的组件（如 Logback Converter）获取容器中的 Bean。
 * 由 {@link cn.nextdev.lightboot.logging.config.LoggingAutoConfiguration}
 * 在容器就绪时注入。
 */
public final class LoggingContextHolder {

    private LoggingContextHolder() {
    }

    private static volatile ApplicationContext applicationContext;

    /**
     * 设置 ApplicationContext（由自动配置调用）。
     *
     * @param ctx Spring 应用上下文
     */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    /**
     * 获取 ApplicationContext。
     *
     * @return Spring 应用上下文，容器未就绪时返回 null
     */
    public static ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * 清除持有的 ApplicationContext（容器关闭时调用，防止静态引用泄漏）。
     */
    public static void clear() {
        applicationContext = null;
    }
}
