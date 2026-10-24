package com.sslauncher.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Thread-safe progress state for long running jobs. */
public final class Progress {
    private volatile String stage = "";
    private volatile String detail = "";
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private volatile boolean indeterminate;
    private final List<Consumer<Progress>> listeners = new CopyOnWriteArrayList<>();
    private volatile long lastNotify;
    private volatile boolean cancelled;

    public void addListener(Consumer<Progress> listener) {
        listeners.add(listener);
    }

    public void setStage(String stage) {
        this.stage = stage;
        notifyListeners(true);
    }

    public void setDetail(String detail) {
        this.detail = detail;
        notifyListeners(false);
    }

    public void setTotal(long total) {
        this.total.set(Math.max(1, total));
        notifyListeners(true);
    }

    public void setDone(long value) {
        done.set(value);
        notifyListeners(false);
    }

    public long getDone() {
        return done.get();
    }

    public long getTotal() {
        return total.get();
    }

    public void addDone(long delta) {
        done.addAndGet(delta);
        notifyListeners(false);
    }

    public void setIndeterminate(boolean indeterminate) {
        this.indeterminate = indeterminate;
        notifyListeners(true);
    }

    public boolean isIndeterminate() {
        return indeterminate;
    }

    public String getStage() {
        return stage;
    }

    public String getDetail() {
        return detail;
    }

    public double fraction() {
        long t = total.get();
        if (t <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) done.get() / t);
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    private void notifyListeners(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastNotify < 80_000_000L) {
            return;
        }
        lastNotify = now;
        for (Consumer<Progress> l : listeners) {
            try {
                l.accept(this);
            } catch (Exception ignored) {
            }
        }
    }
}
