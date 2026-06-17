package io.mrarm.irc.chatlib;

import java.util.List;

public class NickUnavailableException extends ChatApiException {

    private final List<String> mTriedNicks;

    public NickUnavailableException(String serverMessage, List<String> triedNicks) {
        super(serverMessage);
        mTriedNicks = triedNicks;
    }

    public List<String> getTriedNicks() {
        return mTriedNicks;
    }

}
