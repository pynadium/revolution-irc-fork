package io.mrarm.irc.protocol.irc;


import io.mrarm.irc.protocol.ChatApiException;

public class NumericReplyException extends ChatApiException {

    private int errorCommandId;
    private String errorMessage;

    public NumericReplyException(int errorCommandId, String errorMessage) {
        super(errorMessage);
    }

    public int getErrorCommandId() {
        return errorCommandId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

}