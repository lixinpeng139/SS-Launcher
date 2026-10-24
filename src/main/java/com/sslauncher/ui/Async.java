package com.sslauncher.ui;

import com.sslauncher.core.Log;
import javafx.application.Platform;

import java.util.function.Consumer;

/** Runs blocking work off the JavaFX thread and delivers results back on it. */
public final class Async {
    private Async() {
    }

    public interface Work {
        void run() throws Exception;
    }

    public interface WorkSupplier<T> {
        T get() throws Exception;
    }

    public static void run(Work work, Runnable onSuccess, Consumer<Throwable> onError) {
        Thread thread = new Thread(() -> {
            try {
                work.run();
                if (onSuccess != null) {
                    Platform.runLater(onSuccess);
                }
            } catch (Throwable e) {
                Log.error("后台任务失败", e);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e));
                }
            }
        }, "ss-async");
        thread.setDaemon(true);
        thread.start();
    }

    public static <T> void supply(WorkSupplier<T> supplier, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Thread thread = new Thread(() -> {
            try {
                T value = supplier.get();
                if (onSuccess != null) {
                    Platform.runLater(() -> onSuccess.accept(value));
                }
            } catch (Throwable e) {
                Log.error("后台任务失败", e);
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(e));
                }
            }
        }, "ss-async");
        thread.setDaemon(true);
        thread.start();
    }
}
