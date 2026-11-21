package dev.modpackupdate.updater;

import java.io.InterruptedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A startup-check budget, independent of the timeout for installation downloads. */
final class CheckSession implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final Downloads downloads;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "modpackupdate-check-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private boolean closed;
    private boolean cancelled;
    final long deadline;

    CheckSession(Downloads downloads, int seconds) {
        this.downloads = downloads;
        int limit = Math.max(2, Math.min(seconds, 600));
        deadline = System.currentTimeMillis() + limit * 1000L;
        timer.schedule(this::cancel, limit, TimeUnit.SECONDS);
    }

    synchronized void cancel() {
        if (closed) return;
        cancelled = true;
        owner.interrupt();
        downloads.cancel();
    }

    synchronized boolean cancelled() { return cancelled; }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新检查已取消或超时");
    }

    @Override public synchronized void close() {
        closed = true;
        timer.shutdownNow();
        if (cancelled) Thread.interrupted();
    }
}
