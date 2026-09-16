package cn.nextdev.lightboot.web.config;

import cn.nextdev.lightboot.logging.trace.MdcTaskDecorator;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.Assert;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 生产级异步任务执行器配置，支持请求上下文传播与优雅停机。
 *
 * <p>核心特性：
 * <ul>
 *   <li>自动将 HTTP 请求上下文传播到异步线程</li>
 *   <li>Caller-Runs 拒绝策略并提供详细日志</li>
 *   <li>支持优雅停机，可配置等待超时</li>
 *   <li>核心线程超时回收，优化资源利用率</li>
 * </ul>
 *
 * <p>通过 {@code application.yml} 以 {@code light-boot.async.executor} 为前缀进行配置：
 * <pre>{@code
 * light-boot:
 *   async:
 *     executor:
 *       core-pool-size: 10
 *       max-pool-size: 20
 *       queue-capacity: 500
 *       keep-alive-seconds: 60
 *       thread-name-prefix: "async-executor-"
 *       allow-core-thread-timeout: true
 *       await-termination-seconds: 60
 * }</pre>
 */
@AutoConfiguration
@EnableConfigurationProperties(AsyncConfig.AsyncExecutorProperties.class)
@EnableAsync
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 默认构造方法。
     */
    public AsyncConfig() {
    }

    /**
     * 创建默认的异步任务执行器 Bean。
     *
     * <p>采用 Caller-Runs 拒绝策略提供背压并防止任务丢失。
     * 自动传播 HTTP 请求上下文（{@link RequestAttributes}）与 MDC（traceId 等），
     * 适用于 Web 应用场景。
     *
     * <p><b>上下文传播组合策略：</b>{@link MdcTaskDecorator} 负责 MDC 的传播与恢复，
     * {@link #wrapWithContext(Runnable)} 负责 {@code RequestAttributes} 的传播与恢复，
     * 两者各司其职、避免重复传播 MDC。组合顺序为
     * {@code mdcTaskDecorator.decorate(wrapWithContext(original))}：
     * 构造时内层 {@code wrapWithContext} 先捕获 RequestAttributes 快照，外层再捕获 MDC 快照；
     * 执行时外层先设置 MDC，内层再设置 RequestAttributes；finally 中内层先恢复
     * RequestAttributes、外层再恢复 MDC（均恢复为子线程原有上下文），保证线程池复用安全。
     *
     * @param properties               线程池配置属性
     * @param mdcTaskDecoratorProvider MDC 传播装饰器的可选注入（由 logging 模块提供，可选）
     * @return 配置完成的任务执行器
     * @throws IllegalArgumentException 当配置参数不合法时抛出
     */
    @Bean("taskExecutor")
    public TaskExecutor taskExecutor(AsyncExecutorProperties properties, ObjectProvider<MdcTaskDecorator> mdcTaskDecoratorProvider) {
        log.info("Initializing async task executor with properties: {}", properties);
        validateProperties(properties);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setAllowCoreThreadTimeOut(properties.isAllowCoreThreadTimeOut());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());

        // 组合传播：RequestAttributes（内层）+ MDC（外层 MdcTaskDecorator）。
        // 通过 ObjectProvider 注入，使 logging 模块禁用（无 MdcTaskDecorator Bean）时
        // 仍可装配 executor，此时退化为仅传播 RequestAttributes。
        MdcTaskDecorator mdcTaskDecorator = mdcTaskDecoratorProvider.getIfAvailable();
        if (mdcTaskDecorator != null) {
            executor.setTaskDecorator(original -> mdcTaskDecorator.decorate(wrapWithContext(original)));
        } else {
            executor.setTaskDecorator(AsyncConfig::wrapWithContext);
        }

        // 自定义线程工厂，统一线程命名
        executor.setThreadFactory(new TaskThreadFactory(properties.getThreadNamePrefix()));

        // 带日志的拒绝策略
        executor.setRejectedExecutionHandler(new RejectedExecutionHandlerWithLogging());

        executor.initialize();

        log.info("Async task executor initialized successfully - corePoolSize: {}, maxPoolSize: {}, queueCapacity: {}",
                properties.getCorePoolSize(), properties.getMaxPoolSize(), properties.getQueueCapacity());

        return executor;
    }

    /**
     * 校验线程池配置参数合法性。
     *
     * @param properties 待校验的配置属性
     * @throws IllegalArgumentException 当参数不满足约束条件时抛出
     */
    private void validateProperties(AsyncExecutorProperties properties) {
        Assert.isTrue(properties.getCorePoolSize() > 0, "corePoolSize must be positive");
        Assert.isTrue(properties.getMaxPoolSize() >= properties.getCorePoolSize(), "maxPoolSize must be >= corePoolSize");
        Assert.isTrue(properties.getQueueCapacity() > 0, "queueCapacity must be positive");
        Assert.isTrue(properties.getKeepAliveSeconds() > 0, "keepAliveSeconds must be positive");
        Assert.isTrue(properties.getAwaitTerminationSeconds() > 0, "awaitTerminationSeconds must be positive");
    }

    /**
     * 将任务包装为带 {@code RequestAttributes} 传播的 Runnable。
     *
     * <p>本方法只负责 HTTP 请求上下文（{@link RequestAttributes}）的传播与恢复；
     * MDC（traceId 等）的传播已交由 {@link MdcTaskDecorator} 负责，避免重复传播。
     *
     * <p>执行完成后恢复异步线程原有的请求上下文（而非直接清除），
     * 防止线程池复用时清除其他请求的上下文。
     *
     * @param task 原始任务
     * @return 包装后的任务，若当前无请求上下文则返回原始任务
     */
    private static Runnable wrapWithContext(Runnable task) {
        RequestAttributes requestContext = RequestContextHolder.getRequestAttributes();

        // 无请求上下文需要传播时直接返回
        if (requestContext == null) {
            return task;
        }

        return () -> {
            RequestAttributes previousRequest = RequestContextHolder.getRequestAttributes();
            try {
                RequestContextHolder.setRequestAttributes(requestContext);
                task.run();
            } finally {
                // 恢复为异步线程原来的请求上下文，而非直接 reset
                // 防止线程池复用时清除其他请求的上下文
                if (previousRequest != null) {
                    RequestContextHolder.setRequestAttributes(previousRequest);
                } else {
                    RequestContextHolder.resetRequestAttributes();
                }
            }
        };
    }

    /**
     * 返回 {@code void} 返回值 {@code @Async} 方法抛出未捕获异常时的处理器。
     *
     * <p>此类异常不会进入 {@code AsyncConfigurer} 的正常异常通道；Spring 默认的
     * {@code SimpleAsyncUncaughtExceptionHandler} 亦会以 ERROR 级别记录。本实现的增量在于
     * 统一 logger 且消息显式包含声明类与方法名，便于定位。
     *
     * @return 异常处理器
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "Unhandled @Async exception in {}#{}",
                method.getDeclaringClass().getSimpleName(), method.getName(), ex);
    }

    /**
     * 自定义线程工厂，统一异步线程命名。
     *
     * <p>创建的线程为非守护线程，确保 JVM 停机时任务可正常完成。
     */
    private static class TaskThreadFactory implements ThreadFactory {
        private final ThreadFactory defaultThreadFactory;
        private final String threadNamePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        /**
         * 创建线程工厂实例。
         *
         * @param threadNamePrefix 线程名前缀
         */
        TaskThreadFactory(String threadNamePrefix) {
            this.defaultThreadFactory = Executors.defaultThreadFactory();
            this.threadNamePrefix = threadNamePrefix;
        }

        /**
         * 创建带统一前缀编号的非守护线程。
         */
        @Override
        public Thread newThread(@NonNull Runnable runnable) {
            Thread thread = defaultThreadFactory.newThread(runnable);
            thread.setName(threadNamePrefix + threadNumber.getAndIncrement());

            // 非守护线程：确保 JVM 停机前任务能够执行完成
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);

            return thread;
        }
    }

    /**
     * 带日志的拒绝策略处理器，记录线程池状态后在调用者线程中执行被拒绝的任务。
     *
     * <p>通过在调用者线程中执行提供背压，防止高负载时任务丢失。
     */
    private static class RejectedExecutionHandlerWithLogging implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            log.warn("Task rejected - active: {}, poolSize: {}, queue: {}, completed: {}",
                    e.getActiveCount(), e.getPoolSize(), e.getQueue().size(), e.getCompletedTaskCount());

            // 在调用者线程中执行（Caller-Runs 策略）
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * 异步执行器线程池配置属性，绑定前缀为 {@code light-boot.async.executor}。
     *
     * <p><b>调优建议：</b>
     * <ul>
     *   <li><b>CPU 密集型：</b>corePoolSize = CPU 核心数，maxPoolSize = CPU 核心数</li>
     *   <li><b>IO 密集型：</b>corePoolSize = CPU 核心数 × 2，maxPoolSize = CPU 核心数 × 4</li>
     *   <li><b>低流量场景：</b>启用 allowCoreThreadTimeOut 以节省资源</li>
     * </ul>
     */
    @Setter
    @Getter
    @ConfigurationProperties(prefix = "light-boot.async.executor")
    public static class AsyncExecutorProperties {

        /**
         * 默认构造方法。
         */
        public AsyncExecutorProperties() {
        }

        /**
         * 核心线程数。
         *
         * <p>注意：默认 {@code allow-core-thread-time-out=true} 时，核心线程空闲超过
         * {@code keep-alive-seconds} 同样会被回收，并非始终保活；需要始终保活可将其设为 {@code false}。
         *
         * <p>CPU 密集型建议设为 CPU 核心数；IO 密集型建议设为 CPU 核心数 × 2
         */
        private int corePoolSize = 10;

        /**
         * 最大线程数 —— 必须大于等于核心线程数。
         *
         * <p>CPU 密集型建议与 corePoolSize 相同；IO 密集型建议 CPU 核心数 × 4 及以上
         */
        private int maxPoolSize = 20;

        /**
         * 队列容量 —— 等待执行的最大任务数。
         *
         * <p>较小值（100-500）：更快触发拒绝策略，内存占用低
         * <br>较大值（1000+）：更高吞吐量，内存占用高
         */
        private int queueCapacity = 500;

        /**
         * 空闲线程存活时间（秒）。
         *
         * <p>影响非核心线程的超时回收；启用 {@code allowCoreThreadTimeOut} 后也影响核心线程
         */
        private int keepAliveSeconds = 60;

        /**
         * 线程名前缀，用于线程识别（如 {@code "async-executor-1"}）。
         */
        private String threadNamePrefix = "async-executor-";

        /**
         * 是否允许核心线程在空闲时超时回收。
         *
         * <p>{@code true}：降低资源占用，但线程回收后可能出现短暂延迟
         * <br>{@code false}：性能更稳定，但基础资源占用更高
         */
        private boolean allowCoreThreadTimeOut = true;

        /**
         * 优雅停机时等待任务完成的最大秒数。
         *
         * <p>短任务建议 30-60 秒；长任务建议 120-300 秒
         */
        private int awaitTerminationSeconds = 60;

        /**
         * 返回线程池配置摘要。
         */
        @Override
        public String toString() {
            return "AsyncExecutorProperties{" +
                    "corePoolSize=" + corePoolSize +
                    ", maxPoolSize=" + maxPoolSize +
                    ", queueCapacity=" + queueCapacity +
                    ", keepAliveSeconds=" + keepAliveSeconds +
                    ", threadNamePrefix='" + threadNamePrefix + '\'' +
                    ", allowCoreThreadTimeOut=" + allowCoreThreadTimeOut +
                    ", awaitTerminationSeconds=" + awaitTerminationSeconds +
                    '}';
        }
    }

}
