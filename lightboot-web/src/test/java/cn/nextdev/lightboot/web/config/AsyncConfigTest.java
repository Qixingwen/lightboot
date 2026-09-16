package cn.nextdev.lightboot.web.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.nextdev.lightboot.logging.trace.MdcTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AsyncConfig 单测：验证 AsyncUncaughtExceptionHandler 以 ERROR 记录日志、
 * implements AsyncConfigurer，以及 wrapWithContext 仅传播 RequestAttributes（MDC 由 MdcTaskDecorator 负责）。
 */
class AsyncConfigTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        RequestContextHolder.resetRequestAttributes();
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * AsyncConfig 实现 AsyncConfigurer。
     */
    @Test
    void implementsAsyncConfigurer() {
        assertThat(new AsyncConfig()).isInstanceOf(org.springframework.scheduling.annotation.AsyncConfigurer.class);
    }

    /**
     * handler 非空，且对异常以 ERROR 级别记录，包含声明类与方法名。
     */
    @Test
    void getAsyncUncaughtExceptionHandler_logsErrorWithThrowable() throws Exception {
        AsyncConfig config = new AsyncConfig();
        AsyncUncaughtExceptionHandler handler = config.getAsyncUncaughtExceptionHandler();
        assertThat(handler).isNotNull();

        Method method = SampleAsyncBean.class.getMethod("boom");
        IllegalStateException ex = new IllegalStateException("async-failure");

        handler.handleUncaughtException(ex, method, new Object[0]);

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("Unhandled @Async exception")
                    .contains(SampleAsyncBean.class.getSimpleName())
                    .contains("boom");
            // throwable 必须随日志事件一起记录
            assertThat(event.getThrowableProxy())
                    .as("Throwable must be attached to the log event")
                    .isNotNull();
            assertThat(event.getThrowableProxy().getClassName())
                    .isEqualTo(IllegalStateException.class.getName());
        });
    }

    /**
     * wrapWithContext 仅负责 RequestAttributes；MDC 传播由 MdcTaskDecorator 负责，
     * 因此经由组合 decorator 后，子线程应同时看到 RequestAttributes 和 MDC（各一次）。
     */
    @Test
    void taskExecutorComposesRequestAttributesAndMdc() throws Exception {
        AsyncConfig config = new AsyncConfig();
        AsyncConfig.AsyncExecutorProperties props = new AsyncConfig.AsyncExecutorProperties();
        // 限小池子加速测试
        props.setCorePoolSize(1);
        props.setMaxPoolSize(1);
        props.setQueueCapacity(1);

        org.springframework.core.task.TaskExecutor executor =
                config.taskExecutor(props, singletonProvider(new MdcTaskDecorator()));

        // 设置请求上下文与 MDC（模拟请求线程）
        RequestAttributes ra = new DummyRequestAttributes();
        RequestContextHolder.setRequestAttributes(ra);
        org.slf4j.MDC.put("traceId", "trace-async-xyz");

        AtomicReference<RequestAttributes> seenRa = new AtomicReference<>();
        AtomicReference<String> seenMdc = new AtomicReference<>();

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            seenRa.set(RequestContextHolder.getRequestAttributes());
            seenMdc.set(org.slf4j.MDC.get("traceId"));
            latch.countDown();
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(seenRa.get()).as("RequestAttributes must be propagated").isSameAs(ra);
        assertThat(seenMdc.get()).as("MDC must be propagated").isEqualTo("trace-async-xyz");

        // 子线程执行后 MDC 已被恢复/清理，提交线程的 MDC 不受影响
        assertThat(org.slf4j.MDC.get("traceId")).isEqualTo("trace-async-xyz");

        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tpte) {
            tpte.shutdown();
        }
    }

    /**
     * 当 logging 模块未启用（无 MdcTaskDecorator Bean）时，executor 仍可装配，
     * decorator 退化为仅传播 RequestAttributes。
     *
     * <p>证明 web 模块对 logging 的依赖是可选的：通过 ObjectProvider 传入空提供者，
     * taskExecutor 不应抛出 NoSuchBeanDefinitionException，且 RequestAttributes 仍被传播。
     */
    @Test
    void taskExecutorWiresWithoutMdcDecorator() throws Exception {
        AsyncConfig config = new AsyncConfig();
        AsyncConfig.AsyncExecutorProperties props = new AsyncConfig.AsyncExecutorProperties();
        props.setCorePoolSize(1);
        props.setMaxPoolSize(1);
        props.setQueueCapacity(1);

        // 模拟 logging 禁用：ObjectProvider 不提供任何 MdcTaskDecorator Bean
        org.springframework.core.task.TaskExecutor executor =
                config.taskExecutor(props, emptyProvider());
        assertThat(executor).isNotNull();

        // 设置请求上下文（模拟请求线程）；不设置 MDC，验证退化路径仍传播 RequestAttributes
        RequestAttributes ra = new DummyRequestAttributes();
        RequestContextHolder.setRequestAttributes(ra);

        AtomicReference<RequestAttributes> seenRa = new AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        executor.execute(() -> {
            seenRa.set(RequestContextHolder.getRequestAttributes());
            latch.countDown();
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(seenRa.get()).as("RequestAttributes must still be propagated without MdcTaskDecorator").isSameAs(ra);

        if (executor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor tpte) {
            tpte.shutdown();
        }
    }

    /**
     * 用于反射获取目标方法的样本 bean。
     */
    public static class SampleAsyncBean {
        public void boom() {
        }
    }

    /**
     * 最小可用 RequestAttributes 实现，仅用于断言引用传播。
     */
    private static final class DummyRequestAttributes implements RequestAttributes {
        @Override
        public Object getAttribute(String name, int scope) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
        }

        @Override
        public void removeAttribute(String name, int scope) {
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) {
        }

        @Override
        public Object resolveReference(String key) {
            return null;
        }

        @Override
        public String getSessionId() {
            return null;
        }

        @Override
        public Object getSessionMutex() {
            return null;
        }
    }

    /**
     * 返回一个始终提供给定单例的 ObjectProvider，用于测试中向 taskExecutor 注入既存的 MdcTaskDecorator。
     */
    private static org.springframework.beans.factory.ObjectProvider<MdcTaskDecorator> singletonProvider(MdcTaskDecorator decorator) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public MdcTaskDecorator getObject() {
                return decorator;
            }

            @Override
            public MdcTaskDecorator getIfAvailable() {
                return decorator;
            }
        };
    }

    /**
     * 返回一个不提供任何 Bean 的 ObjectProvider，用于模拟 logging 禁用（无 MdcTaskDecorator Bean）的场景。
     */
    private static org.springframework.beans.factory.ObjectProvider<MdcTaskDecorator> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public MdcTaskDecorator getObject() {
                throw new IllegalStateException("No MdcTaskDecorator bean available");
            }

            @Override
            public MdcTaskDecorator getIfAvailable() {
                return null;
            }
        };
    }
}
