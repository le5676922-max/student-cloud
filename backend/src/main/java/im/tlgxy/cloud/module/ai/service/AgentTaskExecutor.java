package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Single-process, bounded admission. A reservation is acquired before the persistent task CAS. */
@Component
public class AgentTaskExecutor {
    private final ThreadPoolExecutor pool;
    private final Semaphore capacity;

    public AgentTaskExecutor() { this(2, 16); }

    AgentTaskExecutor(int workers, int queued) {
        capacity = new Semaphore(workers + queued);
        AtomicInteger sequence = new AtomicInteger();
        pool = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queued), runnable -> {
                    Thread thread = new Thread(runnable, "agent-task-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void reserve() {
        if (pool.isShutdown() || !capacity.tryAcquire()) throw new BizException("后台任务繁忙，请稍后确认；任务仍保持待确认状态");
    }

    public void release() { capacity.release(); }

    /** After success the worker owns the reservation; after rejection the caller still owns it. */
    public void submit(Runnable job) {
        pool.execute(() -> {
            try { job.run(); }
            finally { release(); }
        });
    }

    @PreDestroy
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
