package io.mrarm.irc.protocol.irc.handlers;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import io.mrarm.irc.protocol.irc.CommandHandler;
import io.mrarm.irc.protocol.irc.InvalidMessageException;
import io.mrarm.irc.protocol.irc.MessagePrefix;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public class PingCommandHandler implements CommandHandler {

    @Override
    public Object[] getHandledCommands() {
        return new Object[]{"PING"};
    }

    @Override
    public void handle(ServerConnectionData connection, MessagePrefix sender, String command, List<String> params,
                       Map<String, String> tags)
            throws InvalidMessageException {
        try {
            connection.getApi().sendCommand("PONG", true,
                    CommandHandler.getParamWithCheck(params, 0));
        } catch (IOException ignored) {
        }
    }

}
