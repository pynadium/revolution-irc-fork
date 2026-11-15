package io.mrarm.irc.message;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.message.MessageListener;

public class DefaultMessageBus implements MessageBus {
    private static final String TAG = "DefaultMessageBus";

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
                dispatch(l, channelName, message, messageId);
            }
        }

        for (MessageListener lst : globalCopy) {
            dispatch(lst, channelName, message, messageId);
        }
    }

    // emit() runs on the "MessagePipeline" thread, where an uncaught exception kills the
    // process. One failing listener must not stop the remaining ones or the pipeline.
    private static void dispatch(MessageListener listener, String channelName,
                                 MessageInfo message, MessageId messageId) {
        try {
            listener.onMessage(channelName, message, messageId);
        } catch (RuntimeException e) {
            Log.e(TAG, "MessageListener threw while handling a message in room=" + channelName, e);
        }
    }
}
