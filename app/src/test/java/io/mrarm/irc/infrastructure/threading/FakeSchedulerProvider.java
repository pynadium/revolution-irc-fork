package io.mrarm.irc.infrastructure.threading;

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

    @Override
    public ThreadFactory newThreadFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public ExecutorService newSerialExecutor(String name) {
        return Executors.newSingleThreadExecutor(newThreadFactory(name));
    }
}
