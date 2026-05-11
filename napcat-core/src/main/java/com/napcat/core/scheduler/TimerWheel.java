package com.napcat.core.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 精确定时轮。
 * 按任务 ID 管理 ScheduledFuture，支持动态增删。
 */
@Slf4j
public class TimerWheel {

    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    public TimerWheel() {
        this.executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "napcat-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 注册一个定时任务。
     * @param taskId 任务唯一 ID
     * @param triggerTime 触发时间
     * @param task 执行体
     */
    public void schedule(String taskId, Instant triggerTime, Runnable task) {
        // 移除同 ID 旧任务
        cancel(taskId);

        long delayMs = Math.max(0, triggerTime.toEpochMilli() - System.currentTimeMillis());
        ScheduledFuture<?> future = executor.schedule(() -> {
            try {
                log.debug("Timer fired: taskId={}", taskId);
                task.run();
            } catch (Exception e) {
                log.error("Timer task error: taskId={}", taskId, e);
            } finally {
                futures.remove(taskId);
            }
        }, delayMs, TimeUnit.MILLISECONDS);

        futures.put(taskId, future);
        log.info("Timer scheduled: taskId={}, triggerIn={}ms, triggerAt={}",
                taskId, delayMs, triggerTime);
    }

    /**
     * 取消指定任务。
     */
    public void cancel(String taskId) {
        ScheduledFuture<?> existing = futures.remove(taskId);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
            log.debug("Timer cancelled: taskId={}", taskId);
        }
    }

    /**
     * 取消所有任务。
     */
    public void cancelAll() {
        futures.forEach((id, f) -> {
            if (!f.isDone()) f.cancel(false);
        });
        futures.clear();
        log.info("All timers cancelled");
    }

    /**
     * 当前活跃任务数。
     */
    public int activeCount() {
        return (int) futures.values().stream().filter(f -> !f.isDone()).count();
    }

    /**
     * 关闭线程池。
     */
    public void shutdown() {
        cancelAll();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("TimerWheel shutdown");
    }
}
