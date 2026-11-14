package io.mrarm.irc.message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.message.MessageListener;

public class DefaultMessageBus implements MessageBus {
    private final Map<String, List<MessageListener>> channelListeners = new HashMap<>();
    private final List<MessageListener> globalListeners = new ArrayList<>();

    @Override
    public void subscribe(String channelName, MessageListener listener) {
        if (channelName == null) {
            synchronized (globalListeners) {
                globalListeners.add(listener);
            }
        } else {
            synchronized (channelListeners) {
                channelListeners.computeIfAbsent(channelName, k -> new ArrayList<>())
                        .add(listener);
            }
        }

    }

    @Override
    public void unsubscribe(String channelName, MessageListener listener) {
        if (channelName == null) {
            synchronized (globalListeners) {
                globalListeners.remove(listener);
            }
        } else {
            synchronized (channelListeners) {
                List<MessageListener> listeners = channelListeners.get(channelName);
                if (listeners != null) {
                    listeners.remove(listener);
                    if (listeners.isEmpty()) {
                        channelListeners.remove(channelName);
                    }
                }
            }
        }
    }

    @Override
    public void emit(String channelName, MessageInfo message, MessageId messageId) {
        List<MessageListener> channelCopy = null;
        List<MessageListener> globalCopy;

        synchronized (channelListeners) {
            List<MessageListener> listeners = channelListeners.get((channelName));
            if (listeners != null)
                channelCopy = new ArrayList<>(listeners);
        }

        synchronized (globalListeners) {
            globalCopy = new ArrayList<>(globalListeners);
        }

        if (channelCopy != null) {
            for (MessageListener l : channelCopy) {
                l.onMessage(channelName, message, messageId);
            }
        }

        for (MessageListener lst : globalCopy) {
            lst.onMessage(channelName, message, messageId);
        }
    }
}
