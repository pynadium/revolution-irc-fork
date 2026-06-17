package io.mrarm.irc.message;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.mrarm.irc.chatlib.dto.MessageId;
import io.mrarm.irc.chatlib.dto.MessageInfo;
import io.mrarm.irc.chatlib.dto.RoomMessageId;
import io.mrarm.irc.storage.db.MessageEntity;

public class DefaultMessagePipeline implements MessagePipeline {

    private final MessagePipelineContext pipelineContext;
    private final MessageBus messageBus;


    public DefaultMessagePipeline(MessagePipelineContext context,
                                  MessageBus messageBus) {
        this.pipelineContext = context;
        this.messageBus = messageBus;
    }

    public final ExecutorService pipelineExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MessagePipeline");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void accept(String channelName, MessageInfo message) {
        Log.d(
                "MessagePipeline",
                "accept: server=" + pipelineContext.serverId +
                        " room=" + channelName +
                        " sender=" + (message.getSender() != null ? message.getSender().getNick() : "<none>") +
                        " text=" + message.getMessage()
        );

        if (channelName == null) return;

        pipelineExecutor.execute(() -> {
            final MessageId messageId;
            try {
                messageId = persist(channelName, message);
            } catch (Throwable t) {
                Log.e("MessagePipeline", "persist failed", t);
                return;
            }

            Log.d("MessagePipeline",
                    "emit: room=" + channelName +
                            " id=" + messageId
            );

            messageBus.emit(channelName, message, messageId);
        });
    }

    @Override
    public void shutdown() {
        pipelineExecutor.shutdown();
    }

    protected MessageId persist(String channelName, MessageInfo message) {
        MessageEntity entity = MessageEntity.from(pipelineContext.serverId, channelName, message);
        return new RoomMessageId(pipelineContext.repository.insertMessage(entity));
    }
}
