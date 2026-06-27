package io.mrarm.irc.protocol.irc;


import io.mrarm.irc.protocol.dto.NickPrefixList;
import io.mrarm.irc.protocol.dto.NickWithPrefix;

public class OneCharNickPrefixParser implements NickPrefixParser {

    private static final OneCharNickPrefixParser instance = new OneCharNickPrefixParser();

    public static OneCharNickPrefixParser getInstance() {
        return instance;
    }

    @Override
    public NickWithPrefix parse(ServerConnectionData connection, String nick) {
        NickPrefixList supportedNickPrefixes = connection.getSupportList().getSupportedNickPrefixes();
        char firstNickChar = nick.charAt(0);
        for (char prefix : supportedNickPrefixes) {
            if (firstNickChar == prefix)
                return new NickWithPrefix(nick.substring(1), new NickPrefixList(prefix + ""));
        }
        return new NickWithPrefix(nick, null);
    }

}
