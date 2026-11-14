package io.mrarm.irc.conversation;

import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.protocol.dto.MessageId;

public interface ConversationListener {
    void onMessage(String channel, ConversationMessage message, MessageId id);
}
