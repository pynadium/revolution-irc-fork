package io.mrarm.irc.protocol;


public class NoSuchChannelException extends ChatApiException {

    public NoSuchChannelException() {
        super("The specified channel does not exist");
    }

}
