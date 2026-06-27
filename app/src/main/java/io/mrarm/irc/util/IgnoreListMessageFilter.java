package io.mrarm.irc.util;

import android.util.Log;

import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.MessageFilter;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public class IgnoreListMessageFilter implements MessageFilter {

    private ServerConfigData mConfig;

    public IgnoreListMessageFilter(ServerConfigData server) {
        mConfig = server;
    }

    @Override
    public boolean filter(ServerConnectionData serverConnectionData, String channel, MessageInfo message) {
        if (mConfig.ignoreList != null && message.getSender() != null) {
            for (ServerConfigData.IgnoreEntry entry : mConfig.ignoreList) {
                if (entry.nick == null && entry.user == null && entry.host == null)
                    continue;
                if (entry.nickRegex == null && entry.userRegex == null && entry.hostRegex == null)
                    entry.updateRegexes();
                if (entry.nickRegex != null && !entry.nickRegex.matcher(message.getSender().getNick()).matches())
                    continue;
                if (entry.userRegex != null && (message.getSender().getUser() == null || !entry.userRegex.matcher(message.getSender().getUser()).matches()))
                    continue;
                if (entry.hostRegex != null && (message.getSender().getHost() == null || !entry.hostRegex.matcher(message.getSender().getHost()).matches()))
                    continue;
                Log.d("IgnoreListMessageFilter", "Ignore message: " + message.getSender().getNick() + " " + message.getMessage());
                return false;
            }
        }
        return true;
    }
}
