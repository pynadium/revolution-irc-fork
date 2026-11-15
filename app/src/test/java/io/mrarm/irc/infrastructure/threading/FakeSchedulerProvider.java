package io.mrarm.irc.infrastructure.threading;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import kotlinx.coroutines.CoroutineDispatcher;
import kotlinx.coroutines.Dispatchers;

/**
 * SchedulerProvider for JVM tests: the I/O and main executors run tasks inline on the calling
 * thread, and reconnect timers go to a {@link FakeDelayScheduler} the test drives by hand.
 */
public class FakeSchedulerProvider implements SchedulerProvider {

    private final FakeDelayScheduler mDelayScheduler;
    private final Executor mDirect = Runnable::run;
    private final List<Throwable> mUncaught = new CopyOnWriteArrayList<>();
    private final List<Thread> mThreads = new CopyOnWriteArrayList<>();

    public FakeSchedulerProvider() {
        this(new FakeDelayScheduler());
    }

    public FakeSchedulerProvider(FakeDelayScheduler delayScheduler) {
        mDelayScheduler = delayScheduler;
    }

    public FakeDelayScheduler getFakeDelayScheduler() {
        return mDelayScheduler;
    }

    @Override
    public CoroutineDispatcher getIoDispatcher() {
        return Dispatchers.getUnconfined();
    }

    @Override
    public DelayScheduler getReconnectionScheduler() {
        return mDelayScheduler;
    }

    @Override
    public Executor getIoExecutor() {
        return mDirect;
    }

    @Override
    public Executor getMainExecutor() {
        return mDirect;
    }

    /**
     * Unlike the production factory, nothing is caught here: an exception that escapes a task
     * reaches the thread's uncaught handler and is recorded, so a test can assert that the code
     * under test contained it itself.
     */
    @Override
    public ThreadFactory newThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) -> mUncaught.add(e));
            mThreads.add(t);
            return t;
        };
    }

    /**
     * Exceptions that escaped threads created by {@link #newThreadFactory(String)}. A thread
     * reports its exception only while it dies, after its executor may already count as
     * terminated: call {@link #joinThreads(long)} first.
     */
    public List<Throwable> getUncaughtExceptions() {
        return mUncaught;
    }

    /** Waits for every thread created by {@link #newThreadFactory(String)} to end. */
    public void joinThreads(long timeoutMillis) throws InterruptedException {
        // By index: a pool replaces a worker killed by an exception, adding a thread meanwhile.
        for (int i = 0; i < mThreads.size(); i++)
            mThreads.get(i).join(timeoutMillis);
    }

    @Override
    public ExecutorService newSerialExecutor(String name) {
        return Executors.newSingleThreadExecutor(newThreadFactory(name));
    }
}
