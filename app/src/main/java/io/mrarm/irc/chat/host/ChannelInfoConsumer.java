package io.mrarm.irc.chat.host;

import java.util.Date;
import java.util.List;

import io.mrarm.irc.protocol.dto.NickWithPrefix;

public interface ChannelInfoConsumer {
    void setCurrentChannelInfo(String topic, String setBy, Date setOn, List<NickWithPrefix> members);
}
