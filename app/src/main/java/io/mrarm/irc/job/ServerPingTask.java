package io.mrarm.irc.job;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.protocol.ChatApi;
import io.mrarm.irc.protocol.irc.IRCConnection;

public class ServerPingTask {

    public static void pingServers(Context ctx, DoneCallback cb) {
        List<ServerConnectionSession> servers =
                ServerConnectionManager.getInstance(ctx).getConnections();
        List<IRCConnection> serversToPing = new ArrayList<>();
        for (ServerConnectionSession c : servers) {
            if (!c.isConnected())
                continue;
            ChatApi api = c.getApiInstance();
            if (api != null && api instanceof IRCConnection)
                serversToPing.add((IRCConnection) api);
        }
        if (serversToPing.isEmpty()) {
            Log.d("ServerPingTask", "No servers to ping");
            cb.onDone();
            return;
        }
        Log.d("ServerPingTask", "Pinging " + serversToPing.size() + " servers");
        AtomicInteger countdownInteger = new AtomicInteger(serversToPing.size());
        for (IRCConnection api : serversToPing) {
            Runnable pingCompleteCb = () -> {
                Log.d("ServerPingTask", "Ping received from a server");
                if (countdownInteger.decrementAndGet() == 0) {
                    Log.d("ServerPingTask", "Task has been completed");
                    cb.onDone();
                }
            };
            api.sendPing((Void v) -> pingCompleteCb.run(),
                    (Exception e) -> pingCompleteCb.run());
            // Sending the ping will either succeed or get us disconnected (we can't get an error
            // from the ping itself, and the disconnect will be handled by other code already).
        }
    }


    public interface DoneCallback {

        void onDone();

    }

}
