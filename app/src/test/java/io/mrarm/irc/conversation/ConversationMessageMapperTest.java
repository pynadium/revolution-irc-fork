package io.mrarm.irc.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Date;

import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.model.ConversationMessageType;
import io.mrarm.irc.protocol.dto.KickMessageInfo;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.NickChangeMessageInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;

public class ConversationMessageMapperTest {
    private static MessageSenderInfo sender(String nick) {
        return new MessageSenderInfo(nick, null, null, null, null);
    }

    @Test
    public void normalMessage_fieldsPreserved() {
        Date date = new Date(1_000_000L);
        MessageInfo.Builder b = new MessageInfo.Builder(sender("alice"), "hello world",
                MessageInfo.MessageType.NORMAL);
        b.setDate(date);
        MessageInfo info = b.build();
        MessageId id = new RoomMessageId(42L);

        ConversationMessage msg = ConversationMessageMapper.map(info, id);

        assertEquals(id, msg.getId());
        assertEquals("alice", msg.getSenderDisplayName());
        assertEquals("hello world", msg.getText());
        assertEquals(ConversationMessageType.NORMAL, msg.getType());
        assertEquals(date, msg.getTimestamp());
        assertFalse(msg.isPlayback());
        assertTrue(msg.getExtra().isEmpty());
    }

    @Test
    public void kickMessage_extraContainsKickedNick() {
        KickMessageInfo info = new KickMessageInfo(sender("alice"), new Date(), "bob", "bad " +
                "behavior");
        ConversationMessage msg = ConversationMessageMapper.map(info, new RoomMessageId(1L));

        assertEquals(ConversationMessageType.KICK, msg.getType());
        assertEquals("bob", msg.getExtra().get("kickedNick"));
        assertEquals("bad behavior", msg.getText());
    }

    @Test
    public void nickChange_extraContainsNewNick() {
        NickChangeMessageInfo info = new NickChangeMessageInfo(sender("alice"), new Date(),
                "alice2");
        ConversationMessage msg = ConversationMessageMapper.map(info, new RoomMessageId(2L));

        assertEquals(ConversationMessageType.NICK_CHANGE, msg.getType());
        assertEquals("alice2", msg.getExtra().get("newNick"));
    }

    @Test
    public void nullSender_handledGracefully() {
        MessageInfo info = new MessageInfo.Builder(null, "server message",
                MessageInfo.MessageType.NOTICE).build();
        ConversationMessage msg = ConversationMessageMapper.map(info, new RoomMessageId(3L));

        assertNull(msg.getSenderDisplayName());
        assertNull(msg.getSenderUUID());
        assertEquals(ConversationMessageType.NOTICE, msg.getType());
    }

    @Test
    public void nullType_defaultsToNormal() {
        MessageInfo info = new MessageInfo.Builder(sender("alice"), "text", null).build();
        ConversationMessage msg = ConversationMessageMapper.map(info, new RoomMessageId(4L));

        assertEquals(ConversationMessageType.NORMAL, msg.getType());
    }
}
