package io.mrarm.irc.message;

import io.mrarm.irc.protocol.dto.MessageInfo;

/**
 * Orchestrates live message flow:
 <pre>
 *   MessageSink.accept(...)
 *      → persist
 *      → assign MessageId
 *      → MessageBus.emit(...)</pre>
 */
public interface MessagePipeline extends MessageSink {
    /**
     * Lifecycle hook. <br>
     * Called when the server session is being torn down.
     */
    void shutdown();

    void accept(String channelName, MessageInfo message);
}
