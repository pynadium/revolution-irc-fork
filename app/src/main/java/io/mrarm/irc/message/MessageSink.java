package io.mrarm.irc.message;

import io.mrarm.irc.protocol.dto.MessageInfo;

public interface MessageSink {
    /**
     * Accept a live message for a logical room.
     <pre>
     * - Must be non-blocking
     * - Must NOT throw on persistence failure
     * - Ordering guarantees are defined by the pipeline, not the caller
     </pre>
     */
    void accept(String roomKey, MessageInfo message);

}
