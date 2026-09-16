package cn.nextdev.lightboot.web.config;

import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.util.Assert;
import org.springframework.util.ErrorHandler;

import java.util.concurrent.RejectedExecutionException;

/**
 * 生产级定时任务调度配置，提供增强的监控与错误处理能力。
 *
 * <p>核心特性：
 * <ul>
 *   <li>可配置的线程池及参数校验</li>
 *   <li>初始化与任务执行的结构化日志</li>
 *   <li>自定义错误处理器，防止任务异常中断调度循环</li>
 *   <li>支持优雅停机，可配置等待超时</li>
 * </ul>
 *
 * <p>通过 {@code application.yml} 以 {@code light-boot.scheduling.thread-pool} 为前缀进行配置：
 * <pre>{@code
 * light-boot:
 *   scheduling:
 *     thread-pool:
 *       pool-size: 10
 *       thread-name-prefix: "scheduled-task-"
 *       wait-for-tasks-to-complete-on-shutdown: true
 *       await-termination-seconds: 60
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(SchedulingConfig.SchedulingThreadPoolProperties.class)
@EnableScheduling
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    private final SchedulingThreadPoolProperties properties;
    private final ObjectProvider<ThreadPoolTaskScheduler> taskSchedulerProvider;
    private ThreadPoolTaskScheduler taskScheduler;

    /**
     * 使用指定的配置属性创建调度配置实例。
     *
     * @param properties            线程池配置属性
     * @param taskSchedulerProvider 容器中 {@code taskScheduler} Bean 的延迟获取器。
     *                              本类为 {@code @AutoConfiguration}（{@code proxyBeanMethods=false}），
     *                              不能直接调用 {@link #taskScheduler()}——那会绕过容器代理
     *                              new 出第二个非托管实例，导致线程池双倍创建
     */
    public SchedulingConfig(SchedulingThreadPoolProperties properties,
                            ObjectProvider<ThreadPoolTaskScheduler> taskSchedulerProvider) {
        this.properties = properties;
        this.taskSchedulerProvider = taskSchedulerProvider;
    }

    /**
     * 配置定时任务注册器，使用容器中的自定义任务调度器 Bean（{@link #taskScheduler()}）。
     * 经 {@link ObjectProvider} 获取，保证拿到的是容器管理的同一实例。
     *
     * @param taskRegistrar 待配置的任务注册器
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        log.info("Configuring scheduled task registrar");
        taskRegistrar.setScheduler(taskSchedulerProvider.getObject());
    }

    /**
     * 创建并配置线程池任务调度器 Bean。
     *
     * <p>调度器特性：
     * <ul>
     *   <li>自定义错误处理器，提供结构化异常日志</li>
     *   <li>初始化过程的详细日志记录</li>
     *   <li>支持优雅停机，可配置等待超时</li>
     * </ul>
     *
     * @return 配置完成的线程池任务调度器
     * @throws IllegalArgumentException 当配置参数不合法时抛出
     */
    @Bean("taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        log.info("Initializing task scheduler with properties: {}", properties);
        validateProperties(properties);

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
        scheduler.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());

        // 自定义错误处理器，捕获任务中的未处理异常
        scheduler.setErrorHandler(new SchedulingErrorHandler());

        scheduler.initialize();

        this.taskScheduler = scheduler;

        log.info("Task scheduler initialized successfully - poolSize: {}, threadNamePrefix: '{}'",
                properties.getPoolSize(), properties.getThreadNamePrefix());

        return scheduler;
    }

    /**
     * 校验调度线程池配置参数合法性。
     *
     * @param properties 待校验的配置属性
     * @throws IllegalArgumentException 当参数不满足约束条件时抛出
     */
    private void validateProperties(SchedulingThreadPoolProperties properties) {
        Assert.isTrue(properties.getPoolSize() > 0, "poolSize must be positive");
        Assert.isTrue(properties.getAwaitTerminationSeconds() > 0, "awaitTerminationSeconds must be positive");
    }

    /**
     * 优雅停机钩子：记录停机日志并显式 shutdown 调度器（容器销毁时其内部也会自动关闭，
     * 此处显式调用是为了拿到明确的日志时机，幂等无害）。
     */
    @PreDestroy
    public void destroy() {
        if (taskScheduler != null) {
            log.info("Shutting down task scheduler gracefully");
            taskScheduler.shutdown();
        }
    }

    /**
     * 定时任务自定义错误处理器，提供结构化异常日志。
     *
     * <p>捕获定时任务中未处理的异常并记录上下文信息，
     * 防止单个任务异常导致整个调度循环静默中断。
     */
    private static class SchedulingErrorHandler implements ErrorHandler {

        /**
         * 按异常类型分级记录：线程池拒绝 ERROR、任务中断 WARN、其他 ERROR（含异常栈）。
         */
        @Override
        public void handleError(@NonNull Throwable t) {
            if (t instanceof RejectedExecutionException) {
                log.error("Scheduled task rejected - thread pool saturated or shutting down: {}", t.getMessage());
            } else if (t instanceof InterruptedException) {
                log.warn("Scheduled task interrupted: {}", t.getMessage());
            } else {
                log.error("Unexpected error in scheduled task", t);
            }
        }
    }

    /**
     * 定时任务调度线程池配置属性，绑定前缀为 {@code light-boot.scheduling.thread-pool}。
     *
     * <p><b>调优建议：</b>
     * <ul>
     *   <li><b>轻量级调度：</b>poolSize = 2-5</li>
     *   <li><b>中等负载调度：</b>poolSize = 5-10</li>
     *   <li><b>重度调度：</b>poolSize = 10-20</li>
     * </ul>
     *
     * <p>定时任务通常为短生命周期且 IO 密集型，因此相比异步执行器，线程池规模一般更小即可满足需求。
     */
    @Setter
    @Getter
    @ConfigurationProperties(prefix = "light-boot.scheduling.thread-pool")
    public static class SchedulingThreadPoolProperties {

        /**
         * 默认构造方法。
         */
        public SchedulingThreadPoolProperties() {
        }

        /**
         * 定时任务线程池大小，默认 {@code 10}。
         *
         * <p>定时任务通常为短生命周期操作（清理任务、周期性检查等）。
         * 轻量级场景可调低至 2-5，重度调度可增至 10-20（见类级调优建议），
         * 仅在有并发执行需求时增加。
         */
        private int poolSize = 10;

        /**
         * 线程名前缀，用于线程识别（如 {@code "scheduled-task-1"}）。
         */
        private String threadNamePrefix = "scheduled-task-";

        /**
         * 优雅停机时是否等待正在执行的任务完成。
         *
         * <p>为 {@code true} 时，调度器将在应用停机前等待运行中的任务执行完成
         * （最长等待 {@link #awaitTerminationSeconds} 秒）。
         */
        private boolean waitForTasksToCompleteOnShutdown = true;

        /**
         * 优雅停机时等待任务完成的最大秒数。
         *
         * <p>建议：常规定时任务 30-60 秒；长时间运行的批处理任务 120-300 秒。
         */
        private int awaitTerminationSeconds = 60;

        /**
         * 返回线程池配置摘要。
         */
        @Override
        public String toString() {
            return "SchedulingThreadPoolProperties{" +
                    "poolSize=" + poolSize +
                    ", threadNamePrefix='" + threadNamePrefix + '\'' +
                    ", waitForTasksToCompleteOnShutdown=" + waitForTasksToCompleteOnShutdown +
                    ", awaitTerminationSeconds=" + awaitTerminationSeconds +
                    '}';
        }
    }

}
