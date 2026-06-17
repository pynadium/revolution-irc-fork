package io.mrarm.irc.chatlib.user;


import java.util.UUID;
import java.util.concurrent.Future;

import io.mrarm.irc.chatlib.ResponseCallback;
import io.mrarm.irc.chatlib.ResponseErrorCallback;

public interface WritableUserInfoApi extends UserInfoApi {

    /**
     * Resolve a user UUID synchronously on the calling thread.
     *
     * <p>This is the safe entry point for protocol-layer code (e.g. command handlers) that runs
     * on the IRC socket thread. Implementations must complete without blocking on an external
     * thread or I/O. The async {@link #resolveUser} overload must NOT be called with {@code .get()}
     * from the socket thread — it offers no such guarantee.</p>
     */
    UUID resolveUserSync(String nick, String user, String host);

    Future<Void> setUserNick(UUID user, String newNick, ResponseCallback<Void> callback,
                             ResponseErrorCallback errorCallback);

    Future<Void> setUserChannelPresence(UUID user, String channel, boolean present, ResponseCallback<Void> callback,
                                        ResponseErrorCallback errorCallback);

    Future<Void> clearAllUsersChannelPresences(ResponseCallback<Void> callback, ResponseErrorCallback errorCallback);

}
