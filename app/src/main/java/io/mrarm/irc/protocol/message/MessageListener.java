package io.mrarm.irc.protocol.message;

import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;

public interface MessageListener {

    void onMessage(String channel, MessageInfo message, MessageId messageId);

}
