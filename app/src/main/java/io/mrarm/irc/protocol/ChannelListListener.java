package io.mrarm.irc.protocol;

import java.util.List;

public interface ChannelListListener {

    void onChannelListChanged(List<String> newList);

    void onChannelJoined(String channel);

    void onChannelLeft(String channel);

}
