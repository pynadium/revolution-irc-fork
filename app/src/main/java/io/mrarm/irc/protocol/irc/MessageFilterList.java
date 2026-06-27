package io.mrarm.irc.protocol.irc;

import java.util.ArrayList;
import java.util.List;

import io.mrarm.irc.protocol.dto.MessageInfo;

public class MessageFilterList {

    private final List<MessageFilter> mMessageFilters = new ArrayList<>();

    public void addMessageFilter(MessageFilter filter) {
        synchronized (mMessageFilters) {
            mMessageFilters.add(filter);
        }
    }

    public void removeMessageFilter(MessageFilter filter) {
        synchronized (mMessageFilters) {
            mMessageFilters.remove(filter);
        }
    }

    public boolean filterMessage(ServerConnectionData connection, String channel, MessageInfo message) {
        synchronized (mMessageFilters) {
            for (MessageFilter filter : mMessageFilters) {
                if (!filter.filter(connection, channel, message))
                    return false;
            }
        }
        return true;
    }

    public boolean filterMessageExcept(ServerConnectionData connection,
                                       String channel,
                                       MessageInfo message,
                                       MessageFilter excludedFilter) {
        synchronized (mMessageFilters) {
            for (MessageFilter filter : mMessageFilters) {
                if (filter == excludedFilter) {
                    continue;
                }
                if (!filter.filter(connection, channel, message))
                    return false;
            }
        }
        return true;
    }

}
