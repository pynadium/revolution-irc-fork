package io.mrarm.irc.protocol.message;


import io.mrarm.irc.protocol.dto.MessageListAfterIdentifier;

public class SimpleMessageListAfterIdentifier implements MessageListAfterIdentifier {

    private final int index;

    public SimpleMessageListAfterIdentifier(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

}
