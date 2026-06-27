package io.mrarm.irc.protocol.irc;


import io.mrarm.irc.protocol.ChatApiException;

public class InvalidMessageException extends ChatApiException {

    public InvalidMessageException() {
        super("The received message is invalid");
    }

    public InvalidMessageException(String message) {
        super(message);
    }

    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }

}
