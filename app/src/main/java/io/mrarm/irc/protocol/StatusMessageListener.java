package io.mrarm.irc.protocol;


import io.mrarm.irc.protocol.dto.StatusMessageInfo;

public interface StatusMessageListener {

    void onStatusMessage(StatusMessageInfo message);

}
