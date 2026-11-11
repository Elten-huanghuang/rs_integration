package com.huanghuang.rsintegration.crafting.planning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 有界的保栈规划调度器。检查点耗尽时间片后暂停原线程，释放计算通道；
 * 下一时间片从同一调用栈继续，不重新搜索，也不把世界对象移到后台。
 */
final class CooperativePlanningExecutor extends AbstractExecutorService {
    private static final ThreadLocal<Job> CURRENT = new ThreadLocal<>();
    private final Object lock = new Object();
    private final Map<Runnable, Job> jobs = new IdentityHashMap<>();
    private final ArrayDeque<Job> ready = new ArrayDeque<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final ScheduledExecutorService timer;
    private final ThreadFactory factory;
    private final int capacity;
    private final long sliceNanos;
    private final long periodNanos;
    private final long timeoutNanos;
    private ScheduledFuture<?> wake;
    private boolean shutdown;

    CooperativePlanningExecutor(int parallelism, int queueCapacity, int sliceMs,
                                int periodMs, int timeoutMs, ThreadFactory factory) {
        if (parallelism < 1 || queueCapacity < 1 || sliceMs < 1 || periodMs < sliceMs || timeoutMs < 0) {
            throw new IllegalArgumentException("invalid planning schedule");
        }
        this.capacity = Math.addExact(parallelism, queueCapacity);
        this.sliceNanos = TimeUnit.MILLISECONDS.toNanos(sliceMs);
        this.periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMs);
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        this.factory = factory;
        for (int i = 0; i < parallelism; i++) {
            Lane lane = new Lane();
            lane.nextWindow = System.nanoTime();
            lanes.add(lane);
        }
        timer = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Override
    public void execute(Runnable command) {
        synchronized (lock) {
            if (shutdown || jobs.size() >= capacity) throw new RejectedExecutionException("planning queue full");
            Job job = new Job(command);
            jobs.put(command, job);
            ready.addLast(job);
            dispatch();
        }
    }

    /** 取消未启动任务立即归还队列位置；已启动线程必须退出后才归还容量。 */
    void remove(Runnable command) {
        synchronized (lock) {
            Job job = jobs.get(command);
            if (job == null) return;
            ready.remove(job);
            if (job.thread == null) jobs.remove(command);
            else job.thread.interrupt();
            dispatch();
            lock.notifyAll();
        }
    }

    int getActiveCount() {
        synchronized (lock) {
            return (int) lanes.stream().filter(lane -> lane.owner != null).count();
        }
    }

    int queuedCount() {
        synchronized (lock) { return ready.size(); }
    }

    static void checkpoint() {
        Job job = CURRENT.get();
        if (job != null) job.checkpoint();
    }

    static long deadline(long fallback) {
        Job job = CURRENT.get();
        return job == null ? fallback : job.deadline;
    }

    /** 所有调用者持有 lock；每个通道每周期最多消耗一个时间片。 */
    private void dispatch() {
        if (wake != null) { wake.cancel(false); wake = null; }
        long now = System.nanoTime();
        long delay = Long.MAX_VALUE;
        for (Lane lane : lanes) {
            if (ready.isEmpty()) break;
            if (lane.owner != null) continue;
            if (now >= lane.nextWindow) {
                lane.until = now + sliceNanos;
                lane.nextWindow = now + periodNanos;
            }
            if (now >= lane.until) {
                delay = Math.min(delay, lane.nextWindow - now);
                continue;
            }
            Job job = ready.removeFirst();
            if (job.cancelled()) {
                if (job.thread == null) jobs.remove(job.command);
                else job.thread.interrupt();
                // 下次唤醒继续清理，避免一个取消任务挡住空闲通道。
                delay = 0L;
                continue;
            }
            lane.owner = job;
            job.lane = lane;
            if (job.thread == null) {
                job.deadline = timeoutNanos == 0L ? Long.MAX_VALUE : now + timeoutNanos;
                job.thread = factory.newThread(job);
                job.thread.start();
            }
        }
        lock.notifyAll();
        if (!ready.isEmpty() && delay != Long.MAX_VALUE && !timer.isShutdown()) {
            wake = timer.schedule(() -> { synchronized (lock) { dispatch(); } },
                    Math.max(0L, delay), TimeUnit.NANOSECONDS);
        }
        if (shutdown && jobs.isEmpty()) timer.shutdown();
    }

    @Override
    public void shutdown() {
        synchronized (lock) { shutdown = true; dispatch(); }
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (lock) {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>();
            for (Job job : new ArrayList<>(jobs.values())) {
                if (job.command instanceof Future<?> future) future.cancel(true);
                if (job.thread == null) { pending.add(job.command); jobs.remove(job.command); }
                else job.thread.interrupt();
            }
            ready.clear();
            timer.shutdownNow();
            lock.notifyAll();
            return pending;
        }
    }

    @Override public boolean isShutdown() { synchronized (lock) { return shutdown; } }
    @Override public boolean isTerminated() { synchronized (lock) { return shutdown && jobs.isEmpty(); } }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        synchronized (lock) {
            while (!isTerminated()) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0L) return false;
                TimeUnit.NANOSECONDS.timedWait(lock, remaining);
            }
            return true;
        }
    }

    private static final class Lane {
        private Job owner;
        private long until;
        private long nextWindow;
    }

    private final class Job implements Runnable {
        private final Runnable command;
        private Thread thread;
        private Lane lane;
        private long deadline;

        private Job(Runnable command) { this.command = command; }

        private boolean cancelled() {
            return command instanceof Future<?> future && future.isCancelled();
        }

        private void checkpoint() {
            // 绝大多数检查点不进入全局锁。
            if (lane != null && System.nanoTime() < lane.until) return;
            synchronized (lock) {
                if (cancelled() || Thread.currentThread().isInterrupted()) throw new CancellationException();
                lane.owner = null;
                lane = null;
                ready.addLast(this);
                dispatch();
                while (lane == null) {
                    try { lock.wait(); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Planning interrupted while paused");
                    }
                    if (cancelled()) throw new CancellationException();
                }
            }
        }

        @Override public void run() {
            CURRENT.set(this);
            try { command.run(); }
            finally {
                CURRENT.remove();
                synchronized (lock) {
                    ready.remove(this);
                    if (lane != null) lane.owner = null;
                    jobs.remove(command);
                    dispatch();
                }
            }
        }
    }
}
