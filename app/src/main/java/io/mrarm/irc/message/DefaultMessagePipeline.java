package io.mrarm.irc.message;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import io.mrarm.irc.config.ChatSettings;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;
import io.mrarm.irc.storage.db.MessageEntity;
import io.mrarm.irc.storage.db.MessageKind;

public class DefaultMessagePipeline implements MessagePipeline {

    // Session-local ids for messages that are deliberately not persisted
    // (ChatSettings.shouldSkipChannelLogging()). Counting down from Long.MAX_VALUE keeps
    // them always greater than any real autoincrement row id, which matters: pagination
    // (loadOlder/loadNewerAsync) compares ids with plain "<"/">" against real DB ids. A
    // negative or small ephemeral id would satisfy "id > lastId" against every real row in
    // the channel, replaying the whole persisted history every time a live message landed
    // at the bottom and the user scrolled - effectively an infinite scroll.
    private static final AtomicLong EPHEMERAL_ID_SEQ = new AtomicLong(Long.MAX_VALUE);

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
        if (entity.kind == MessageKind.CHANNEL && ChatSettings.shouldSkipChannelLogging())
            return new RoomMessageId(EPHEMERAL_ID_SEQ.getAndDecrement());
        return new RoomMessageId(pipelineContext.repository.insertMessage(entity));
    }
}
