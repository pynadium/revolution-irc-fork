package io.mrarm.irc.protocol.irc.cap;


import java.util.Map;

import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.CommandHandler;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public abstract class Capability implements CommandHandler {

    public abstract String[] getNames();

    public boolean shouldEnableCapability(ServerConnectionData connection, CapabilityEntryPair capability) {
        return true;
    }

    public boolean isBlockingNegotationFinish() {
        return false;
    }

    public void onEnabled(ServerConnectionData connection) {
    }

    public void onDisabled(ServerConnectionData connection) {
    }

    public void processMessage(MessageInfo.Builder message, Map<String, String> tags) {
    }

}
