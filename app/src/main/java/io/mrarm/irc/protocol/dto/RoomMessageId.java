package io.mrarm.irc.protocol.dto;

public class RoomMessageId implements MessageId {
    private final long id;

    public RoomMessageId(long id) {
        this.id = id;
    }

    public long getId() {
        return id;
    }

    @Override
    public String toString() {
        return Long.toString(id);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof RoomMessageId) &&
                ((RoomMessageId) obj).id == this.id;
    }

    public static class Parser implements MessageId.Parser {

        @Override
        public MessageId parse(String str) {
            // STRICT: always return a valid ID
            // If str is malformed, fail loudly instead of producing null.
            try {
                return new RoomMessageId(Long.parseLong(str));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Invalid RoomMessageId string: '" + str + "'", e
                );
            }
        }
    }
}
