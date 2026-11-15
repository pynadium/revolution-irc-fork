package io.mrarm.irc.infrastructure.threading;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple utility for running background work on a shared IO thread pool
 * and posting results back to the main thread.
 */
public class AppAsyncExecutor {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Async-IO");
        t.setDaemon(true);
        return t;
    });

    private AppAsyncExecutor() {
        // Utility class; no instances.
    }

    /**
     * Fire-and-forget variant: run work on background thread, then run optional UI callback.
     *
     * @return a Future<?> representing the background task.
     */
    public static Future<?> io(Runnable work, Runnable uiCallback) {
        return IO.submit(() -> {
            try {
                work.run();
            } catch (Exception e) {
                Log.e("Async", "Background work failed", e);
            }
            if (uiCallback != null)
                MAIN.post(uiCallback);
            return null;
        });
    }

    /**
     * Generic variant: run background work that returns a value, then deliver result on UI thread.
     *
     * <p>If the work throws or returns {@code null}, the callback receives {@code fallback}
     * instead, so UI code never has to handle a {@code null} result. There is deliberately no
     * overload without a fallback.
     *
     * @return a Future<?> representing the background task.
     */
    public static <T> Future<?> io(Supplier<T> work, T fallback, Consumer<T> uiCallback) {
        return IO.submit(() -> {
            T result = null;
            try {
                result = work.get();
            } catch (Exception e) {
                Log.e("Async", "Async work failed, delivering the fallback", e);
            }
            final T finalResult = result != null ? result : fallback;
            if (uiCallback != null)
                MAIN.post(() -> uiCallback.accept(finalResult));
            return null;
        });
    }

    /**
     * Runs a runnable directly on the main thread.
     * Useful for posting UI updates without spawning new threads.
     */
    public static void ui(Runnable uiCallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread
            uiCallback.run();
        } else {
            MAIN.post(uiCallback);
        }
    }

    /**
     * Executes background work without any UI callback.
     */
    public static Future<?> io(Runnable work) {
        return io(work, null);
    }
}
