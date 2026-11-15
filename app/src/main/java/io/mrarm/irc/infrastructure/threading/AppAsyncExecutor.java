package io.mrarm.irc.infrastructure.threading;

import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Simple utility for running background work on a shared IO thread pool
 * and posting results back to the main thread.
 *
 * <p>Both threads come from the current {@link SchedulerProvider} ({@code ioExecutor} and
 * {@code mainExecutor}), resolved on every call so tests can swap the provider.
 */
public class AppAsyncExecutor {

    private AppAsyncExecutor() {
        // Utility class; no instances.
    }

    private static SchedulerProvider provider() {
        return SchedulerProviderHolder.get();
    }

    private static Future<?> submitIo(Callable<Void> body) {
        FutureTask<Void> task = new FutureTask<>(body);
        provider().getIoExecutor().execute(task);
        return task;
    }

    /**
     * Fire-and-forget variant: run work on background thread, then run optional UI callback.
     *
     * @return a Future<?> representing the background task.
     */
    public static Future<?> io(Runnable work, Runnable uiCallback) {
        return submitIo(() -> {
            try {
                work.run();
            } catch (Exception e) {
                Log.e("Async", "Background work failed", e);
            }
            if (uiCallback != null)
                provider().getMainExecutor().execute(uiCallback);
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
        return submitIo(() -> {
            T result = null;
            try {
                result = work.get();
            } catch (Exception e) {
                Log.e("Async", "Async work failed, delivering the fallback", e);
            }
            final T finalResult = result != null ? result : fallback;
            if (uiCallback != null)
                provider().getMainExecutor().execute(() -> uiCallback.accept(finalResult));
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
            provider().getMainExecutor().execute(uiCallback);
        }
    }

    /**
     * Executes background work without any UI callback.
     */
    public static Future<?> io(Runnable work) {
        return io(work, null);
    }
}
