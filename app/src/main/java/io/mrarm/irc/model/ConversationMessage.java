package io.mrarm.irc.model;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

import io.mrarm.irc.chatlib.dto.MessageId;

public class ConversationMessage {
    private final MessageId id;
    private final String senderDisplayName;
    private final UUID senderUUID;
    private final String text;
    private final ConversationMessageType type;
    private final Date timestamp;
    private final boolean isPlayback;
    private final Map<String, String> extra;

    public ConversationMessage(MessageId id, String senderDisplayName, UUID senderUUID,
                               String text, ConversationMessageType type, Date timestamp,
                               boolean isPlayback, Map<String, String> extra) {
        this.id = id;
        this.senderDisplayName = senderDisplayName;
        this.senderUUID = senderUUID;
        this.text = text;
        this.type = type;
        this.timestamp = timestamp;
        this.isPlayback = isPlayback;
        this.extra = extra;
    }

    public MessageId getId() { return id; }
    public String getSenderDisplayName() { return senderDisplayName; }
    public UUID getSenderUUID() { return senderUUID; }
    public String getText() { return text; }
    public ConversationMessageType getType() { return type; }
    public Date getTimestamp() { return timestamp; }
    public boolean isPlayback() { return isPlayback; }
    public Map<String, String> getExtra() { return extra; }
}
