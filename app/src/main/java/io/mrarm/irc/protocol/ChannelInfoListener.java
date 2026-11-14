package io.mrarm.irc.protocol;


import java.util.Date;
import java.util.List;

import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.NickWithPrefix;

public interface ChannelInfoListener {

    void onMemberListChanged(List<NickWithPrefix> newMembers);

    void onTopicChanged(String newTopic, MessageSenderInfo newTopicSetBy, Date newTopicSetOn);

}
