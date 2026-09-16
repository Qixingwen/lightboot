package cn.nextdev.lightboot.logging.trace;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 拷贝 MDC 上下文到子线程并在执行后恢复其原有 MDC 的 TaskDecorator。
 *
 * <p>用于让 {@code @Async} / 线程池派发的工作继承父线程的 traceId 等 MDC，并在子线程执行后
 * 恢复其原有 MDC（线程池复用安全）。
 *
 * <p>本类只负责 MDC 的传播与恢复；HTTP {@code RequestAttributes} 的传播由使用方的异步线程池配置
 * 负责（如 web 模块的 {@code AsyncConfig} 会将其与本装饰器组合为嵌套 decorator），各司其职，避免重复传播。
 */
public class MdcTaskDecorator implements TaskDecorator {

    /**
     * 默认构造方法。
     */
    public MdcTaskDecorator() {
    }

    /**
     * 包装任务：在提交线程捕获 MDC 快照，子线程执行前套用、执行后恢复其原有 MDC。
     */
    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交线程捕获 MDC 快照
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            // 记录子线程（池内复用线程）执行前的 MDC，用于 finally 恢复
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                } else {
                    MDC.clear();
                }
                runnable.run();
            } finally {
                // 恢复为子线程原有 MDC（而非直接 clear），防止线程池复用时
                // 误清除同一池内线程上并发的其它请求上下文
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }
}
