package cn.nextdev.lightboot.logging.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MdcTaskDecorator 单测：验证 MDC 向子线程的传播，以及线程池复用时的清理/恢复（防泄漏）。
 */
class MdcTaskDecoratorTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void cleanup() throws InterruptedException {
        MDC.clear();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 子线程通过 decorator 能看到提交线程的 MDC。
     */
    @Test
    void decorate_propagatesMdcToChildThread() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        MDC.put("traceId", "trace-from-parent");

        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get("traceId")));

        pool.submit(task).get();

        assertThat(seen.get()).isEqualTo("trace-from-parent");
    }

    /**
     * 提交线程无 MDC 时，子线程执行期间 MDC 为空（不会读到陈旧值）。
     */
    @Test
    void decorate_clearsMdcWhenParentHasNone() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();
        MDC.clear();

        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get("traceId")));

        pool.submit(task).get();

        assertThat(seen.get()).isNull();
    }

    /**
     * 线程池复用安全：先跑一个设置了 MDC 的任务污染池内线程，
     * 再跑第二个无 MDC 的任务，第二个任务应看到空 MDC —— 证明 decorator
     * 在 finally 中恢复（清除）了池内线程的上下文，不会泄漏到后续任务。
     */
    @Test
    void decorate_restoresPooledThreadContextPreventingLeakage() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        // 第一个任务：在池内线程上写入并保留陈旧 MDC 的"模拟"
        // 直接在池内线程设置 MDC（绕过 decorator），模拟"线程已带脏上下文"
        pool.submit(() -> MDC.put("traceId", "stale-from-previous-task")).get();

        // 第二个任务：提交线程无 MDC，decorator 应清除池内线程的陈旧值
        MDC.clear();
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.get("traceId")));

        pool.submit(task).get();

        assertThat(seen.get())
                .as("Pooled thread must not leak stale MDC from a prior task")
                .isNull();
    }

    /**
     * decorator 必须恢复池内线程"原有"的 MDC（而非无条件清除）。
     *
     * <p>场景：池内线程原本持有 MDC={@code pooled-owned}（模拟并发请求的上下文）。
     * 提交线程带 MDC={@code parent-trace} 派发任务；任务执行期间子线程应看到
     * {@code parent-trace}；任务结束后子线程必须恢复为 {@code pooled-owned}，
     * 证明不会误清除同一池内线程上并发的其它上下文。
     */
    @Test
    void decorate_restoresPooledThreadsPriorMdcAfterTask() throws Exception {
        MdcTaskDecorator decorator = new MdcTaskDecorator();

        // 池内线程原本持有一个 MDC 值
        pool.submit(() -> MDC.put("traceId", "pooled-owned")).get();

        // 提交线程设置自己的 MDC
        MDC.put("traceId", "parent-trace");

        AtomicReference<String> seenDuring = new AtomicReference<>();
        AtomicReference<String> seenAfterRestore = new AtomicReference<>();

        // 任务执行期间读取 MDC（应见 parent-trace），并在外层手动验证恢复
        Runnable task = decorator.decorate(() -> seenDuring.set(MDC.get("traceId")));
        pool.submit(task).get();

        // 提交线程无 MDC，decorator 会先把池内线程恢复到 previous，再 clear 期间值
        // 用裸 Runnable 直接读取池内线程当前的 MDC（未经 decorator 改写）
        MDC.clear();
        pool.submit(() -> seenAfterRestore.set(MDC.get("traceId"))).get();

        assertThat(seenDuring.get())
                .as("Child thread must see parent MDC during task")
                .isEqualTo("parent-trace");
        assertThat(seenAfterRestore.get())
                .as("Pooled thread's prior MDC must be restored after task")
                .isEqualTo("pooled-owned");
    }
}
