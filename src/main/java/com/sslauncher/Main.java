package com.sslauncher;

import com.sslauncher.core.AppPaths;
import com.sslauncher.core.Log;

/**
 * Plain entry point. Kept separate from {@link LauncherApp} so that the JavaFX
 * runtime is never asked to launch a class that itself extends Application
 * from the classpath (which produces a noisy warning).
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        AppPaths.init();
        Log.init();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> Log.error("Uncaught exception on " + t.getName(), e));
        tunePulseRate();
        LauncherApp.main(args);
    }

    /**
     * JavaFX runs its animation pulse at 60 Hz by default. On a 120/144/165 Hz
     * display that leaves motion visibly uneven, so match the panel — capped
     * at 120 Hz to keep the launcher's CPU and GPU use reasonable. Set the
     * property before the toolkit starts; 60 Hz screens keep the default.
     */
    private static void tunePulseRate() {
        if (System.getProperty("javafx.animation.pulse") != null) {
            return;
        }
        try {
            int hz = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDisplayMode().getRefreshRate();
            if (hz >= 90) {
                System.setProperty("javafx.animation.pulse", Integer.toString(Math.min(hz, 120)));
            }
        } catch (Throwable ignored) {
            // AWT is not available in every environment; the default is fine.
        }
    }
}
