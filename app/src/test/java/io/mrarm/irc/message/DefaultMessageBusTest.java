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
import java.util.List;

import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;
import io.mrarm.irc.protocol.message.MessageListener;

/**
 * Fan-out of {@link DefaultMessageBus}: a listener that throws must not stop the others nor
 * escape emit(), and listeners may change their subscription while a message is being delivered.
 * Robolectric is needed only for {@code android.util.Log}, which logs the swallowed exception.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class DefaultMessageBusTest {

    private static final String CHANNEL = "#test";

    private DefaultMessageBus mBus;
    private List<String> mCalls;

    @Before
    public void setUp() {
        mBus = new DefaultMessageBus();
        mCalls = new ArrayList<>();
    }

    @Test
    public void throwingChannelListener_otherChannelAndGlobalListenersStillCalled() {
        mBus.subscribe(CHANNEL, recording("channel-1"));
        mBus.subscribe(CHANNEL, throwing("channel-throws"));
        mBus.subscribe(CHANNEL, recording("channel-2"));
        mBus.subscribe(null, recording("global"));

        mBus.emit(CHANNEL, message("hi"), new RoomMessageId(1L));

        assertEquals(Arrays.asList("channel-1", "channel-throws", "channel-2", "global"), mCalls);
    }

    @Test
    public void throwingGlobalListener_laterGlobalListenersStillCalled() {
        mBus.subscribe(null, throwing("global-throws"));
        mBus.subscribe(null, recording("global"));

        mBus.emit(CHANNEL, message("hi"), new RoomMessageId(1L));

        assertEquals(Arrays.asList("global-throws", "global"), mCalls);
    }

    @Test
    public void throwingListener_isCalledAgainForTheNextMessage() {
        mBus.subscribe(CHANNEL, throwing("channel-throws"));

        mBus.emit(CHANNEL, message("one"), new RoomMessageId(1L));
        mBus.emit(CHANNEL, message("two"), new RoomMessageId(2L));

        assertEquals(Arrays.asList("channel-throws", "channel-throws"), mCalls);
    }

    @Test
    public void listenerUnsubscribingItselfDuringEmit_restStillCalledOnce() {
        MessageListener[] self = new MessageListener[1];
        self[0] = (channel, message, id) -> {
            mCalls.add("once");
            mBus.unsubscribe(CHANNEL, self[0]);
        };
        mBus.subscribe(CHANNEL, self[0]);
        mBus.subscribe(CHANNEL, recording("after"));

        mBus.emit(CHANNEL, message("one"), new RoomMessageId(1L));
        mBus.emit(CHANNEL, message("two"), new RoomMessageId(2L));

        assertEquals(Arrays.asList("once", "after", "after"), mCalls);
    }

    @Test
    public void listenerSubscribingDuringEmit_receivesOnlyLaterMessages() {
        mBus.subscribe(null, (channel, message, id) -> {
            if ("one".equals(message.getMessage()))
                mBus.subscribe(null, recording("late-" + message.getMessage()));
        });

        mBus.emit(CHANNEL, message("one"), new RoomMessageId(1L));
        assertTrue(mCalls.isEmpty());

        mBus.emit(CHANNEL, message("two"), new RoomMessageId(2L));
        assertEquals(Arrays.asList("late-one"), mCalls);
    }

    @Test
    public void channelListener_notCalledForOtherChannels() {
        mBus.subscribe(CHANNEL, recording("channel"));
        mBus.subscribe(null, recording("global"));

        mBus.emit("#other", message("hi"), new RoomMessageId(1L));

        assertEquals(Arrays.asList("global"), mCalls);
    }

    @Test
    public void unsubscribeLastChannelListener_thenEmit_noCalls() {
        MessageListener listener = recording("channel");
        mBus.subscribe(CHANNEL, listener);
        mBus.unsubscribe(CHANNEL, listener);
        mBus.unsubscribe(CHANNEL, listener);

        mBus.emit(CHANNEL, message("hi"), new RoomMessageId(1L));

        assertTrue(mCalls.isEmpty());
    }

    private MessageListener recording(String name) {
        return (channel, message, id) -> mCalls.add(name);
    }

    private MessageListener throwing(String name) {
        return (channel, message, id) -> {
            mCalls.add(name);
            throw new IllegalStateException(name);
        };
    }

    private static MessageInfo message(String text) {
        return new MessageInfo.Builder(new MessageSenderInfo("bob", null, null, null, null),
                text, MessageInfo.MessageType.NORMAL).build();
    }
}
