package io.mrarm.irc.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.mrarm.irc.infrastructure.threading.FakeSchedulerProvider;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;
import io.mrarm.irc.protocol.message.MessageListener;

/**
 * Error paths and lifecycle of {@link DefaultMessagePipeline}: a listener or a bus that throws,
 * a persist that fails, and messages that arrive around {@code shutdown()}.
 * <p>
 * The pipeline runs on a real single thread from {@link FakeSchedulerProvider}, whose threads do
 * not catch anything: an exception the pipeline fails to contain shows up in
 * {@link FakeSchedulerProvider#getUncaughtExceptions()}. Room is not involved: {@code persist()}
 * is overridden to hand out sequential ids, or to throw for chosen messages.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DefaultMessagePipelineTest {

    private static final String CHANNEL = "#test";
    private static final long TIMEOUT_MS = 5000L;

    private FakeSchedulerProvider mSchedulers;
    private DefaultMessageBus mBus;
    private RecordingPipeline mPipeline;
    private Recorder mRecorder;

    @Before
    public void setUp() {
        mSchedulers = new FakeSchedulerProvider();
        mBus = new DefaultMessageBus();
        mPipeline = new RecordingPipeline(mBus, mSchedulers);
        mRecorder = new Recorder();
    }

    @Test
    public void throwingListener_otherListenersAndLaterMessagesStillDelivered() throws Exception {
        mBus.subscribe(CHANNEL, (channel, message, id) -> {
            throw new IllegalStateException("listener failure");
        });
        mBus.subscribe(CHANNEL, mRecorder);

        mPipeline.accept(CHANNEL, message("one"));
        mPipeline.accept(CHANNEL, message("two"));
        drain();

        assertEquals(Arrays.asList("one", "two"), mRecorder.texts);
        assertNoUncaught();
    }

    @Test
    public void throwingGlobalListener_doesNotKillPipelineThread() throws Exception {
        // Same position as the notification subscriber installed by SessionInitializer,
        // which can throw SQLiteException when the database has been closed.
        mBus.subscribe(null, (channel, message, id) -> {
            throw new RuntimeException("database closed");
        });
        mBus.subscribe(CHANNEL, mRecorder);

        mPipeline.accept(CHANNEL, message("one"));
        mPipeline.accept(CHANNEL, message("two"));
        drain();

        assertEquals(Arrays.asList("one", "two"), mRecorder.texts);
        assertNoUncaught();
    }

    @Test
    public void throwingBus_pipelineKeepsProcessing() throws Exception {
        List<String> emitted = new CopyOnWriteArrayList<>();
        MessageBus failingBus = new DefaultMessageBus() {
            @Override
            public void emit(String channelName, MessageInfo message, MessageId messageId) {
                emitted.add(message.getMessage());
                if ("one".equals(message.getMessage()))
                    throw new IllegalStateException("bus failure");
            }
        };
        RecordingPipeline pipeline = new RecordingPipeline(failingBus, mSchedulers);

        pipeline.accept(CHANNEL, message("one"));
        pipeline.accept(CHANNEL, message("two"));
        drain(pipeline);

        assertEquals(Arrays.asList("one", "two"), emitted);
        assertNoUncaught();
    }

    @Test
    public void failedPersist_messageNotEmittedAndNextMessageProcessed() throws Exception {
        mBus.subscribe(CHANNEL, mRecorder);
        mPipeline.failOn("broken");

        mPipeline.accept(CHANNEL, message("before"));
        mPipeline.accept(CHANNEL, message("broken"));
        mPipeline.accept(CHANNEL, message("after"));
        drain();

        assertEquals(Arrays.asList("before", "broken", "after"), mPipeline.persisted);
        assertEquals(Arrays.asList("before", "after"), mRecorder.texts);
        assertNoUncaught();
    }

    @Test
    public void emittedIdIsTheOneReturnedByPersist() throws Exception {
        mBus.subscribe(CHANNEL, mRecorder);

        mPipeline.accept(CHANNEL, message("one"));
        mPipeline.accept(CHANNEL, message("two"));
        drain();

        assertEquals(Arrays.asList(1L, 2L), mRecorder.ids);
    }

    @Test
    public void messagesAreEmittedInAcceptOrder() throws Exception {
        mBus.subscribe(CHANNEL, mRecorder);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            expected.add("m" + i);
            mPipeline.accept(CHANNEL, message("m" + i));
        }
        drain();

        assertEquals(expected, mRecorder.texts);
    }

    @Test
    public void shutdown_deliversMessagesAlreadyQueued() throws Exception {
        // Hold the pipeline thread on the first message so the others are still queued
        // when shutdown() is called.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mBus.subscribe(CHANNEL, (channel, message, id) -> {
            if ("first".equals(message.getMessage())) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
        });
        mBus.subscribe(CHANNEL, mRecorder);

        mPipeline.accept(CHANNEL, message("first"));
        assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mPipeline.accept(CHANNEL, message("second"));
        mPipeline.accept(CHANNEL, message("third"));
        mPipeline.shutdown();
        release.countDown();
        awaitTermination(mPipeline);

        assertEquals(Arrays.asList("first", "second", "third"), mRecorder.texts);
        assertNoUncaught();
    }

    @Test
    public void acceptAfterShutdown_dropsMessageWithoutThrowing() throws Exception {
        mBus.subscribe(CHANNEL, mRecorder);
        mPipeline.shutdown();

        // Must not throw: the caller is the socket thread, which only catches IOException.
        mPipeline.accept(CHANNEL, message("late"));
        awaitTermination(mPipeline);

        assertTrue(mPipeline.persisted.isEmpty());
        assertTrue(mRecorder.texts.isEmpty());
        assertNoUncaught();
    }

    @Test
    public void shutdown_isIdempotent() throws Exception {
        mPipeline.shutdown();
        mPipeline.shutdown();
        awaitTermination(mPipeline);

        assertTrue(mPipeline.pipelineExecutor.isTerminated());
    }

    private static MessageInfo message(String text) {
        return new MessageInfo.Builder(new MessageSenderInfo("bob", null, null, null, null),
                text, MessageInfo.MessageType.NORMAL).build();
    }

    private void drain() throws InterruptedException {
        drain(mPipeline);
    }

    private void drain(DefaultMessagePipeline pipeline) throws InterruptedException {
        pipeline.shutdown();
        awaitTermination(pipeline);
    }

    private void awaitTermination(DefaultMessagePipeline pipeline) throws InterruptedException {
        assertTrue("pipeline did not terminate",
                pipeline.pipelineExecutor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mSchedulers.joinThreads(TIMEOUT_MS);
    }

    private void assertNoUncaught() {
        assertEquals(Collections.emptyList(), mSchedulers.getUncaughtExceptions());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pipeline whose persist() records the message and returns 1, 2, 3, … instead of using Room. */
    private static class RecordingPipeline extends DefaultMessagePipeline {
        final List<String> persisted = new CopyOnWriteArrayList<>();
        private final AtomicLong mNextId = new AtomicLong(1L);
        private volatile String mFailOn;

        RecordingPipeline(MessageBus bus, FakeSchedulerProvider schedulers) {
            super(new MessagePipelineContext(UUID.randomUUID(), null), bus, schedulers);
        }

        void failOn(String text) {
            mFailOn = text;
        }

        @Override
        protected MessageId persist(String channelName, MessageInfo message) {
            persisted.add(message.getMessage());
            if (message.getMessage().equals(mFailOn))
                throw new IllegalStateException("insert failed");
            return new RoomMessageId(mNextId.getAndIncrement());
        }
    }

    private static class Recorder implements MessageListener {
        final List<String> texts = new CopyOnWriteArrayList<>();
        final List<Long> ids = new CopyOnWriteArrayList<>();

        @Override
        public void onMessage(String channel, MessageInfo message, MessageId messageId) {
            texts.add(message.getMessage());
            ids.add(((RoomMessageId) messageId).getId());
        }
    }
}
