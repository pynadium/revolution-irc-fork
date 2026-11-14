package io.mrarm.irc.conversation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.model.ConversationMessageType;
import io.mrarm.irc.protocol.dto.KickMessageInfo;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.NickChangeMessageInfo;
import io.mrarm.irc.protocol.dto.TopicWhoTimeMessageInfo;

public class ConversationMessageMapper {
    static ConversationMessage map(MessageInfo info, MessageId id) {
        MessageSenderInfo sender = info.getSender();
        String senderDisplayName = sender != null ? sender.getNick() : null;
        UUID senderUUID = sender != null ? sender.getUserUUID() : null;

        return new ConversationMessage(id, senderDisplayName, senderUUID, info.getMessage(),
                mapType(info.getType()), info.getDate(), info.isPlayback(), mapExtra(info));
    }

    public static ConversationMessageType mapType(MessageInfo.MessageType type) {
        if (type == null) {
            return ConversationMessageType.NORMAL;
        }
        return ConversationMessageType.valueOf(type.name());
    }

    public static Map<String, String> mapExtra(MessageInfo info) {
        Map<String, String> extra = new HashMap<>();
        if (info instanceof NickChangeMessageInfo) {
            extra.put("newNick", ((NickChangeMessageInfo) info).getNewNick());
        } else if (info instanceof KickMessageInfo) {
            extra.put("kickedNick", ((KickMessageInfo) info).getKickedNick());
        } else if (info instanceof TopicWhoTimeMessageInfo) {
            TopicWhoTimeMessageInfo t = (TopicWhoTimeMessageInfo) info;
            extra.put("setBy", t.getSetBy() != null ? t.getSetBy().getNick() : null);
            extra.put("setOn", String.valueOf(t.getSetOnDate().getTime()));
        }
        // ChannelModeMessageInfo.entries non appiattito in questo cut —
        // ChatLogViewerActivity non lo rende oggi, non serve.
        return extra;
    }
}
