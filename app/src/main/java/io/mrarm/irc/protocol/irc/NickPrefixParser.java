package io.mrarm.irc.protocol.irc;

import io.mrarm.irc.protocol.dto.NickWithPrefix;

public interface NickPrefixParser {

    NickWithPrefix parse(ServerConnectionData connection, String nick);

}
