package im.tlgxy.cloud.module.ai.service;

import im.tlgxy.cloud.core.exception.core.BizException;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded synchronous requests: preserve the servlet's authenticated context, cancel upstream I/O. */
final class AiChatSessions implements AutoCloseable {
    private final Map<String, Session> active = new HashMap<>();
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "ai-chat-deadline");
        thread.setDaemon(true);
        return thread;
    });

    AiChatSessions() { timer.setRemoveOnCancelPolicy(true); }

    synchronized Session open(String id, int owner, int maximum, Duration budget) {
        if (active.containsKey(id)) throw new BizException("此对话正在处理中，请勿重复提交");
        if (active.size() >= maximum || active.values().stream().filter(s -> s.owner == owner).count() >= 2) {
            throw new BizException("AI 助手当前繁忙，请等待已有对话结束后再试");
        }
        Session session = new Session(id, owner, budget);
        active.put(id, session);
        session.deadline = timer.schedule(() -> session.stop("本轮 AI 对话已超时，请缩小问题范围后重试"),
                budget.toMillis(), TimeUnit.MILLISECONDS);
        return session;
    }

    synchronized void cancel(String id, int owner) {
        Session session = active.get(id);
        // Never reveal whether a different user's request exists.
        if (session != null && session.owner == owner) session.stop("已停止本轮 AI 对话");
    }

    @Override
    public synchronized void close() {
        active.values().forEach(s -> s.stop("AI 服务正在停止"));
        timer.shutdownNow();
    }

    final class Session implements AutoCloseable {
        private final String id;
        private final int owner;
        private final long expires = System.nanoTime();
        private final long budgetNanos;
        private final Thread requestThread = Thread.currentThread();
        private ScheduledFuture<?> deadline;
        private volatile String stopped;
        private CompletableFuture<?> pending;
        private Closeable upstream;
        private boolean completed;

        Session(String id, int owner, Duration budget) {
            this.id = id;
            this.owner = owner;
            this.budgetNanos = budget.toNanos();
        }

        void check() {
            if (stopped != null) throw new BizException(stopped);
            if (System.nanoTime() - expires >= budgetNanos) {
                stop("本轮 AI 对话已超时，请缩小问题范围后重试");
                throw new BizException(stopped);
            }
        }

        Duration remaining(int roundSeconds) {
            check();
            return Duration.ofNanos(Math.max(1, Math.min(TimeUnit.SECONDS.toNanos(roundSeconds),
                    budgetNanos - (System.nanoTime() - expires))));
        }

        synchronized void track(CompletableFuture<?> future) {
            pending = future;
            if (stopped != null) future.cancel(true);
        }

        synchronized void track(Closeable body) {
            upstream = body;
            if (stopped != null) closeBody();
        }

        synchronized void stop(String reason) {
            if (completed || stopped != null) return;
            stopped = reason;
            if (pending != null) pending.cancel(true);
            closeBody();
            requestThread.interrupt();
        }

        private void closeBody() {
            if (upstream != null) try { upstream.close(); } catch (IOException ignored) { }
        }

        @Override
        public void close() {
            // Always take the registry lock before the session lock (same order as cancel/shutdown).
            synchronized (AiChatSessions.this) {
                synchronized (this) {
                    completed = true;
                    if (deadline != null) deadline.cancel(false);
                    closeBody();
                    active.remove(id, this);
                    if (stopped != null && Thread.currentThread() == requestThread) Thread.interrupted();
                }
            }
        }
    }
}
