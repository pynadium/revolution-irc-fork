package io.mrarm.irc.protocol.message;


import io.mrarm.irc.protocol.dto.MessageListAfterIdentifier;

public class SimpleMessageListBeforeIdentifier implements MessageListAfterIdentifier {

    private final int index;

    public SimpleMessageListBeforeIdentifier(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

}
