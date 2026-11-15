package io.mrarm.irc.infrastructure.threading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * DelayScheduler on a virtual clock. Nothing runs until the test calls {@link #runDue()} or
 * {@link #advanceBy(long)}. Like a Handler, scheduling the same runnable twice queues it twice,
 * and {@link #cancel(Runnable)} removes every queued copy.
 */
public class FakeDelayScheduler implements DelayScheduler {

    private static final class Task {
        final long due;
        final long seq;
        final long delay;
        final Runnable runnable;

        Task(long due, long seq, long delay, Runnable runnable) {
            this.due = due;
            this.seq = seq;
            this.delay = delay;
            this.runnable = runnable;
        }
    }

    private final List<Task> mTasks = new ArrayList<>();
    private long mNow = 0L;
    private long mSeq = 0L;

    @Override
    public synchronized void schedule(long delayMillis, Runnable runnable) {
        long delay = Math.max(0L, delayMillis);
        mTasks.add(new Task(mNow + delay, mSeq++, delay, runnable));
    }

    @Override
    public synchronized void cancel(Runnable runnable) {
        mTasks.removeIf(t -> t.runnable == runnable);
    }

    /** Runs every task due at the current virtual time, including tasks those tasks post. */
    public void runDue() {
        advanceBy(0L);
    }

    /** Moves the clock forward, running due tasks in time order (ties in posting order). */
    public void advanceBy(long millis) {
        long target;
        synchronized (this) {
            target = mNow + millis;
        }
        while (true) {
            Task next;
            synchronized (this) {
                next = mTasks.stream()
                        .filter(t -> t.due <= target)
                        .min(Comparator.<Task>comparingLong(t -> t.due).thenComparingLong(t -> t.seq))
                        .orElse(null);
                if (next == null) {
                    mNow = target;
                    return;
                }
                mTasks.remove(next);
                mNow = next.due;
            }
            next.runnable.run();
        }
    }

    /** Delays, as originally requested, of the tasks still queued, in posting order. */
    public synchronized List<Long> pendingDelays() {
        List<Long> ret = new ArrayList<>();
        mTasks.stream().sorted(Comparator.comparingLong(t -> t.seq)).forEach(t -> ret.add(t.delay));
        return ret;
    }

    public synchronized int pendingCount() {
        return mTasks.size();
    }
}
