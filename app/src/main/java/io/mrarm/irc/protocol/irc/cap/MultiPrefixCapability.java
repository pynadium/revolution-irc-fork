package io.mrarm.irc.protocol.irc.cap;


import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.irc.InvalidMessageException;
import io.mrarm.irc.protocol.irc.MessagePrefix;
import io.mrarm.irc.protocol.irc.MultiNickPrefixParser;
import io.mrarm.irc.protocol.irc.OneCharNickPrefixParser;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public class MultiPrefixCapability extends Capability {

    @Override
    public String[] getNames() {
        return new String[]{"multi-prefix"};
    }

    @Override
    public String[] getHandledCommands() {
        return new String[0];
    }

    @Override
    public void onEnabled(ServerConnectionData connection) {
        connection.setNickPrefixParser(MultiNickPrefixParser.getInstance());
    }

    @Override
    public void onDisabled(ServerConnectionData connection) {
        connection.setNickPrefixParser(OneCharNickPrefixParser.getInstance());
    }

    @Override
    public void handle(ServerConnectionData connection, MessagePrefix sender, String command, List<String> params, Map<String, String> tags) throws InvalidMessageException {
    }

}
