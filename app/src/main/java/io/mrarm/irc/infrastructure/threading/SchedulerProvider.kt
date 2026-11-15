package io.mrarm.irc.infrastructure.threading

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

interface DelayScheduler {
    fun schedule(delayMillis: Long, runnable: Runnable)
    fun cancel(runnable: Runnable)
}

class HandlerDelayScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : DelayScheduler {
    override fun schedule(delayMillis: Long, runnable: Runnable) {
        if (delayMillis <= 0L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMillis)
        }
    }

    override fun cancel(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
}

interface SchedulerProvider {
    val ioDispatcher: CoroutineDispatcher
    val reconnectionScheduler: DelayScheduler

    /** Shared pool for background I/O. A task that throws is logged, never fatal. */
    val ioExecutor: Executor

    /** Runs tasks on the Android main thread. */
    val mainExecutor: Executor

    /**
     * Factory for dedicated threads: named "name-n", daemon, and a RuntimeException reaching the
     * top of the thread is logged instead of killing the process.
     */
    fun newThreadFactory(name: String): ThreadFactory

    /** Single-thread executor with its own named thread, for work that must keep its order. */
    fun newSerialExecutor(name: String): ExecutorService
}

class DefaultSchedulerProvider : SchedulerProvider {
    private val mainHandler = Handler(Looper.getMainLooper())

    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val reconnectionScheduler: DelayScheduler = HandlerDelayScheduler(mainHandler)

    override val ioExecutor: Executor = GuardedExecutorService(
        Executors.newCachedThreadPool(GuardedThreadFactory("Async-IO")), "Async-IO"
    )

    override val mainExecutor: Executor = Executor { mainHandler.post(it) }

    override fun newThreadFactory(name: String): ThreadFactory = GuardedThreadFactory(name)

    override fun newSerialExecutor(name: String): ExecutorService = GuardedExecutorService(
        Executors.newSingleThreadExecutor(GuardedThreadFactory(name)), name
    )
}

/**
 * Names threads "name-n", marks them daemon, and logs a RuntimeException that reaches the top of
 * the thread: on Android an uncaught exception on any thread kills the whole process.
 */
class GuardedThreadFactory(private val name: String) : ThreadFactory {
    private val count = AtomicInteger()

    override fun newThread(r: Runnable): Thread {
        val t = Thread({
            try {
                r.run()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Uncaught exception on thread ${Thread.currentThread().name}", e)
            }
        }, "$name-${count.incrementAndGet()}")
        t.isDaemon = true
        return t
    }

    private companion object {
        const val TAG = "GuardedThreadFactory"
    }
}

/**
 * Wraps every task given to [execute] so a RuntimeException is logged and the worker survives.
 * On a plain Executors pool such an exception kills the worker thread, and with it the process.
 * submit() is safe either way: its Future captures the exception.
 */
class GuardedExecutorService(
    private val delegate: ExecutorService,
    private val name: String
) : AbstractExecutorService() {

    override fun execute(command: Runnable) {
        delegate.execute {
            try {
                command.run()
            } catch (e: RuntimeException) {
                Log.e(TAG, "Task failed on $name", e)
            }
        }
    }

    override fun shutdown() = delegate.shutdown()
    override fun shutdownNow(): MutableList<Runnable> = delegate.shutdownNow()
    override fun isShutdown(): Boolean = delegate.isShutdown
    override fun isTerminated(): Boolean = delegate.isTerminated
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean =
        delegate.awaitTermination(timeout, unit)

    private companion object {
        const val TAG = "GuardedExecutorService"
    }
}

object SchedulerProviderHolder {
    @Volatile
    private var provider: SchedulerProvider = DefaultSchedulerProvider()

    @JvmStatic
    fun get(): SchedulerProvider = provider

    @JvmStatic
    fun override(provider: SchedulerProvider) {
        this.provider = provider
    }

    @JvmStatic
    fun reset() {
        provider = DefaultSchedulerProvider()
    }
}

class ManagedCoroutineScope(dispatcher: CoroutineDispatcher) {
    private val job = SupervisorJob()
    val scope: CoroutineScope = CoroutineScope(job + dispatcher)

    fun cancel() {
        job.cancel()
    }
}
