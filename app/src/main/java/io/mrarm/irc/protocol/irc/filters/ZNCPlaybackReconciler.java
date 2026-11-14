package io.mrarm.irc.protocol.irc.filters;

import java.util.List;
import java.util.Map;

import io.mrarm.irc.infrastructure.threading.AppAsyncExecutor;
import io.mrarm.irc.message.MessageSink;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.MessageFilter;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public class ZNCPlaybackReconciler {

    void reconcile(ServerConnectionData connection,
                   Map<String, List<MessageInfo>> bufferedMessages,
                   MessageFilter skipFilter) {
        if (bufferedMessages.isEmpty()) return;

        AppAsyncExecutor.io(() -> processBuffered(connection, bufferedMessages, skipFilter));
    }

    private void processBuffered(ServerConnectionData connection,
                                 Map<String, List<MessageInfo>> bufferedMessages,
                                 MessageFilter skipFilter) {
        MessageSink sink = connection.getMessageSink();
        if (sink == null) {
            return;
        }

        for (Map.Entry<String, List<MessageInfo>> entry : bufferedMessages.entrySet()) {
            String channel = entry.getKey();
            List<MessageInfo> messages = entry.getValue();
            for (MessageInfo message : messages) {
                message.setPlayback(true);
                sink.accept(channel, message);
            }
        }
    }
}
