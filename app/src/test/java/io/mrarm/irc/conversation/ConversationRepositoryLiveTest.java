package io.mrarm.irc.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import io.mrarm.irc.message.DefaultMessageBus;
import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.model.ConversationMessageType;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;

public class ConversationRepositoryLiveTest {

    @Test
    public void subscribe_receivesTranslatedMessage() {
        DefaultMessageBus bus = new DefaultMessageBus();
        ConversationRepositoryImpl repo = new ConversationRepositoryImpl(null, bus);

        List<ConversationMessage> received = new ArrayList<>();
        ConversationListener listener = (ch, msg, id) -> received.add(msg);

        repo.subscribe("test", listener);

        MessageSenderInfo sender = new MessageSenderInfo("bob", null, null, null, null);
        MessageInfo info = new MessageInfo.Builder(sender, "hello",
                MessageInfo.MessageType.NORMAL).build();
        RoomMessageId id = new RoomMessageId(99L);

        bus.emit("test", info, id);

        assertEquals(1, received.size());
        assertEquals("bob", received.get(0).getSenderDisplayName());
        assertEquals("hello", received.get(0).getText());
        assertEquals(ConversationMessageType.NORMAL, received.get(0).getType());
    }

    @Test
    public void unsubscribe_stopsReceivingMessages() {
        DefaultMessageBus bus = new DefaultMessageBus();
        ConversationRepositoryImpl repo = new ConversationRepositoryImpl(null, bus);

        List<ConversationMessage> received = new ArrayList<>();
        ConversationListener listener = (ch, msg, id) -> received.add(msg);

        repo.subscribe("test", listener);
        repo.unsubscribe("test", listener);

        MessageInfo info = new MessageInfo.Builder(new MessageSenderInfo("alice", null, null,
                null, null), "should not arrive", MessageInfo.MessageType.NORMAL).build();
        bus.emit("test", info, new RoomMessageId(1L));

        assertTrue(received.isEmpty());
    }

    @Test
    public void subscribe_nullBus_doesNotCrash() {
        ConversationRepositoryImpl repo = new ConversationRepositoryImpl(null);
        ConversationListener listener = (ch, msg, id) -> {
        };
        repo.subscribe("test", listener);   // no-op, must not throw
        repo.unsubscribe("test", listener); // no-op, must not throw
    }
}