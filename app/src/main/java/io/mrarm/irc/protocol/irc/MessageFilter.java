package io.mrarm.irc.protocol.irc;


import io.mrarm.irc.protocol.dto.MessageInfo;

public interface MessageFilter {

    boolean filter(ServerConnectionData connection, String channel, MessageInfo messageInfo);

}
