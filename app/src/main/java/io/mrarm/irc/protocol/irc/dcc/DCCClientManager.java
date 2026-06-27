package io.mrarm.irc.protocol.irc.dcc;


import io.mrarm.irc.protocol.irc.MessagePrefix;
import io.mrarm.irc.protocol.irc.ServerConnectionData;

public abstract class DCCClientManager {

    public DCCClientManager() {
        //
    }

    public abstract void onFileOffered(ServerConnectionData connection, MessagePrefix sender, String filename,
                                       String address, int port, long fileSize);

    public abstract void onFileOfferedUsingReverse(ServerConnectionData connection, MessagePrefix sender,
                                                   String filename, long fileSize, int uploadId);

}
