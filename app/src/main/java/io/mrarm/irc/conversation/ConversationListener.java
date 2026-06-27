package io.mrarm.irc.conversation;

import io.mrarm.irc.chatlib.dto.MessageId;
import io.mrarm.irc.model.ConversationMessage;

public interface ConversationListener {
    void onMessage(String channel, ConversationMessage message, MessageId id);
}
