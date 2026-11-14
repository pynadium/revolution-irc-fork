package io.mrarm.irc.protocol.dto;

public interface MessageId {

// NOTE:
// This abstraction exists to decouple message identity from storage implementation.
// Even though RoomMessageId is currently the only implementation, MessageId is used
// across UI, storage, and pagination boundaries and must remain serializable via toString.
// Do NOT collapse this into a primitive unless message identity is redesigned globally.

    interface Parser {
        MessageId parse(String str);
    }
}
