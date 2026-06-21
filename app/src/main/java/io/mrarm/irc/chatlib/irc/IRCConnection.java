package io.mrarm.irc.chatlib.irc;


import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocket;

import io.mrarm.irc.chatlib.ChatApiException;
import io.mrarm.irc.chatlib.NickUnavailableException;
import io.mrarm.irc.chatlib.ResponseCallback;
import io.mrarm.irc.chatlib.ResponseErrorCallback;
import io.mrarm.irc.chatlib.dto.MessageInfo;
import io.mrarm.irc.chatlib.dto.StatusMessageInfo;
import io.mrarm.irc.chatlib.dto.WhoisInfo;
import io.mrarm.irc.chatlib.irc.handlers.MessageCommandHandler;
import io.mrarm.irc.chatlib.irc.handlers.NickCommandHandler;
import io.mrarm.irc.chatlib.irc.handlers.PongCommandHandler;
import io.mrarm.irc.chatlib.irc.handlers.WhoisCommandHandler;
import io.mrarm.irc.chatlib.user.SimpleUserInfoApi;
import io.mrarm.irc.chatlib.util.SettableFuture;
import io.mrarm.irc.chatlib.util.SimpleRequestExecutor;

public class IRCConnection extends ServerConnectionApi {

    private static final MessageCommandHandler selfMessageHandler = new MessageCommandHandler();
    static {
        // This instance only ever locally replays our own outgoing messages (see
        // sendMessageInternal below) - it must never run CTCP auto-reply/DCC logic on them.
        selfMessageHandler.setLocalEchoOnly(true);
    }

    private static final String[] AUTH_COMMAND_PREFIXES = new String[]{"PASS ", "OPER", "PRIVMSG NickServ :IDENTIFY ",
            "NICKSERV IDENTIFY "};

    private static final String TAG = "IRC CONNECTION";

    private Charset charset; // Connection configuration state
    private Socket socket; // Transport state - It does indicate connection lifecycle. weather the ir a TCP connection open or not.
    private InputStream socketInputStream; // Transport mechanic - moves bytes
    private OutputStream socketOutputStream; // Transport mechanic - moves bytes
    private MessageHandler inputHandler; // a Processor - Not state
    private ResponseCallback<Void> connectCallback; // Transient coordinator state
    private ResponseErrorCallback connectErrorCallback; // Transient coordinator state
    private final List<DisconnectListener> disconnectListeners = new ArrayList<>(); // observer infrastructure

    private SimpleRequestExecutor executor = new SimpleRequestExecutor(); // Threading infrastructure

    public IRCConnection() {
        super(new ServerConnectionData());
        inputHandler = new MessageHandler(getServerConnectionData());
        getServerConnectionData().setUserInfoApi(new SimpleUserInfoApi());

    }

    private void sendCommandRaw(String string, boolean flush) throws IOException {
        synchronized (socketOutputStream) {
            byte[] data = (string + "\r\n").getBytes(charset);
            if (data.length > 512)
                throw new IOException("Too long message");
            socketOutputStream.write(data);
            String printStr = string;
            for (String s : AUTH_COMMAND_PREFIXES) {
                if (string.regionMatches(true, 0, s, 0, s.length())) {
                    printStr = s + "***";
                    break;
                }
            }
            Log.d("Sent: ", printStr);
            if (flush)
                socketOutputStream.flush();
        }
    }

    private void sendCommand(boolean flush, String command, boolean isLastArgFullLine, String... args) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append(command); // TODO: validate
        builder.append(' ');
        int l = args != null ? args.length : 0;
        for (int i = 0; i < l; i++) {
            if (i > 0)
                builder.append(' ');
            if (i == l - 1 && isLastArgFullLine) {
                builder.append(':');
                // TODO: validate
            } else {
                // TODO: validate
            }
            builder.append(args[i]);
        }
        sendCommandRaw(builder.toString(), flush);
    }

    @Override
    public void sendCommand(String command, boolean isLastArgFullLine, String... args) throws IOException {
        sendCommand(true, command, isLastArgFullLine, args);
    }

    private String readCommand() throws IOException {
        // TODO verify specifications about max message lenght. It should be 512? RFC 1459
        byte[] buf = new byte[1024];
        int i, v;

        // TODO Smelly: If a malicious server (or MITM) sends a line longer than 1024 bytes
        //  without \r\n, the command gets silently split across two readCommand() calls.
        //  Basically the first call read the 1024 bytes, then return to the
        //  handleInput loop - while(true)
        //  The second call then picks up the remaning bytes from the stream and process any
        //  injected command as legitimate.
        for (i = 0; i < 1024; i++) {
            v = socketInputStream.read();
            if (v == -1)
                throw new IOException("read() returned -1");
            if (v == '\n' || v == '\r') {
                if (i == 0) {
                    --i;
                    continue;
                }
                break;
            }
            buf[i] = (byte) v;
        }
        return new String(buf, 0, i, charset);
    }

    // TODO check IOException handling here. Something's seems off
    private void handleInput() {
        try {
            while (true) {
                String command = readCommand();
                Log.i("Got: ", command);

                try {
                    // NOTE: Socket read loop
                    // MessageHandler parses line
                    // Dispatches to CommandHandlers
                    // MessageCommandHandler is invoked here
                    inputHandler.handleLine(command);
                } catch (InvalidMessageException e) {
                    getServerConnectionData().getServerStatusData().addMessage(new StatusMessageInfo(
                            null, new Date(), StatusMessageInfo.MessageType.UNHANDLED_MESSAGE, command));
                }
            }
        } catch (IOException e) {

            Log.d(TAG,"handleInput() got a IOException: exception -> ", e);
            // Failure handling: disconnect warnings bypass normal channel routing;
            // they go straight to conversation state

            Log.d(TAG,"handleInput() setting socket streams to null");
            socketInputStream = null;
            synchronized (socketOutputStream) {
                socketOutputStream = null;
            }

            Log.d(TAG,"handleInput() notifying disconnection to Channels, Servers and ");
            getServerConnectionData().addLocalMessageToAllChannels(
                    new MessageInfo(null, new Date(), null, MessageInfo.MessageType.DISCONNECT_WARNING));
            getServerConnectionData().getServerStatusData().addMessage(
                    new StatusMessageInfo(null, new Date(), StatusMessageInfo.MessageType.DISCONNECT_WARNING, null));
            getServerConnectionData().getCommandHandlerList().notifyDisconnected();

            synchronized (this) {
                socket = null;
                ResponseErrorCallback cb = connectErrorCallback;
                connectErrorCallback = null;
                if (cb != null) cb.onError(e);
            }
            synchronized (disconnectListeners) {
                for (DisconnectListener listener : disconnectListeners) {
                    listener.onDisconnected(this, e);
                }
            }
        }
    }

    public Future<Void> connect(IRCConnectionRequest request, ResponseCallback<Void> callback,
                                ResponseErrorCallback errorCallback) {
        SettableFuture<Void> f = new SettableFuture<>();
        executor.queue(() -> {
            try {
                synchronized (this) {
                    if (socket != null)
                        throw new RuntimeException("Already connected");
                    connectCallback = (Void v) -> {
                        f.set(v);
                        callback.onResponse(v);
                    };
                    connectErrorCallback = (Exception e) -> {
                        f.setExecutionException(e);
                        errorCallback.onError(e);
                    };
                    connectSync(request);
                }
            } catch (Exception exception) {
                errorCallback.onError(exception);
                f.setExecutionException(exception);
            }
        });
        return f;
    }

    public void disconnect(boolean cleanly) {
        if (socket != null) {
            if (cleanly) {
                try {
                    if (socketInputStream != null)
                        socketInputStream.close();
                    if (socketOutputStream != null)
                        socketOutputStream.close();
                } catch (IOException ignored) {
                }
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    public Future<Void> disconnect(ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            disconnect(true);
            return null;
        }, callback, errorCallback);
    }

    public void addDisconnectListener(DisconnectListener listener) {
        synchronized (disconnectListeners) {
            disconnectListeners.add(listener);
        }
    }

    public void removeDisconnectListener(DisconnectListener listener) {
        synchronized (disconnectListeners) {
            disconnectListeners.remove(listener);
        }
    }

    // TODO: validate params

    @Override
    public Future<Void> quit(String message, ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            sendCommand("QUIT", true, message);
            disconnect(true);
            return null;
        }, callback, errorCallback);
    }

    public Future<Void> sendPing(ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        final String pingId = UUID.randomUUID().toString();
        SettableFuture<Void> ret = new SettableFuture<>();
        executor.queue(ret, () -> {
            if (getServerConnectionData().getCommandHandlerList().getHandler(PongCommandHandler.class).onRequested(
                    pingId, (Void v) -> {
                        executor.queue(() -> {
                            ret.set(v);
                            if (callback != null)
                                callback.onResponse(v);
                        });
                    }, (String s, int i, String e) -> {
                        NumericReplyException exception = new NumericReplyException(i, e);
                        if (errorCallback != null)
                            errorCallback.onError(exception);
                        ret.setExecutionException(exception);
                    }))
                sendCommand("PING", true, pingId);
        }, errorCallback);
        return ret;
    }

    @Override
    public Future<WhoisInfo> sendWhois(String nick, ResponseCallback<WhoisInfo> callback, ResponseErrorCallback errorCallback) {
        SettableFuture<WhoisInfo> ret = new SettableFuture<>();
        executor.queue(ret, () -> {
            if (getServerConnectionData().getCommandHandlerList().getHandler(WhoisCommandHandler.class).onRequested(
                    nick, (WhoisInfo info) -> {
                        executor.queue(() -> {
                            ret.set(info);
                            if (callback != null)
                                callback.onResponse(info);
                        });
                    }, (String s, int i, String e) -> {
                        NumericReplyException exception = new NumericReplyException(i, e);
                        if (errorCallback != null)
                            errorCallback.onError(exception);
                        ret.setExecutionException(exception);
                    }))
                sendCommand("WHOIS", false, nick);
        }, errorCallback);
        return ret;
    }

    @Override
    public Future<Void> joinChannels(List<String> channels, ResponseCallback<Void> callback,
                                     ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            StringBuilder cmd = new StringBuilder();
            for (String channel : channels) {
                if (channel.length() == 0)
                    continue;
                if (!getServerConnectionData().getSupportList().getSupportedChannelTypes().contains(channel.charAt(0))) {
                    getServerConnectionData().onChannelJoined(channel);
                    continue;
                }
                if (cmd.length() > 0)
                    cmd.append(",");
                cmd.append(channel);
            }
            if (cmd.length() > 0)
                sendCommand("JOIN", true, cmd.toString());
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> leaveChannel(String channel, String reason, ResponseCallback<Void> callback,
                                     ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            if (channel.length() == 0)
                return null;
            if (!getServerConnectionData().getSupportList().getSupportedChannelTypes().contains(channel.charAt(0)))
                getServerConnectionData().onChannelLeft(channel);
            else
                sendCommand("PART", true, channel, reason);
            return null;
        }, callback, errorCallback);
    }

    public Future<Void> sendCommandRaw(String command, ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            sendCommandRaw(command, true);
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> sendCommand(String command, boolean isLastArgFullLine, String[] args,
                                    ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            sendCommand(command, isLastArgFullLine, args);
            return null;
        }, callback, errorCallback);
    }

    // NOTE: Self-message path
    // - outgoing messages are injected back into the same pipeline
    // - no distinction between local echo vs server echo
    // Any message pipeline change must preserve this behavior
    private void sendMessageInternal(String cmd, String channel, String message) throws IOException {
        try {
            List<String> params = new ArrayList<>();
            params.add(channel);
            params.add(message);
            selfMessageHandler.handle(getServerConnectionData(), new MessagePrefix(getServerConnectionData().getUserNick()), cmd, params, null);
        } catch (Exception ignored) {
            // it failed, but we don't really care - the message might have been sent to a channel which we have not
            // joined, which is perfectly valid but will cause the code above to raise an exception
        }
        sendCommand(cmd, true, channel, message);
    }

    public Future<Void> sendMessage(String channel, String message, boolean notice, boolean split,
                                    ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return executor.queue(() -> {
            String cmd = notice ? "NOTICE" : "PRIVMSG";
            if (split) {
                String[] messages = MessageSplitHelper.split(getServerConnectionData(), channel, message, notice);
                for (String submsg : messages)
                    sendMessageInternal(cmd, channel, submsg);
            } else {
                sendMessageInternal(cmd, channel, message);
            }
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> sendMessage(String channel, String message, ResponseCallback<Void> callback,
                                    ResponseErrorCallback errorCallback) {
        return sendMessage(channel, message, false, true, callback, errorCallback);
    }

    @Override
    public Future<Void> sendNotice(String channel, String message, ResponseCallback<Void> callback,
                                   ResponseErrorCallback errorCallback) {
        return sendMessage(channel, message, true, true, callback, errorCallback);
    }

    private void connectSync(IRCConnectionRequest request) throws IOException {
        try {
            getServerConnectionData().reset();
            charset = request.getCharset();
            if (request.isUsingSSL()) {
                socket = request.getSSLSocketFactory().createSocket(request.getServerIP(), request.getServerPort());
                HostnameVerifier hostnameVerifier = request.getSSLHostnameVerifier();
                if (hostnameVerifier != null) {
                    SSLSocket sslSocket = (SSLSocket) socket;
                    sslSocket.setUseClientMode(true);
                    sslSocket.startHandshake();
                    if (!hostnameVerifier.verify(request.getServerIP(), sslSocket.getSession()))
                        throw new IOException("Failed to verify hostname: " + request.getServerIP());
                }
            } else {
                socket = new Socket(request.getServerIP(), request.getServerPort());
            }
            socket.setKeepAlive(true);
            socketInputStream = socket.getInputStream();
            socketOutputStream = socket.getOutputStream();
            sendCommand(false, "CAP", false, "LS", "302");
            if (request.getServerPass() != null)
                sendCommand(false, "PASS", request.getServerPass().contains(" ") ||
                                request.getServerPass().length() == 0 || request.getServerPass().startsWith(":"),
                        request.getServerPass());
            connectRequestNick(request.getNickList(), 0);
            sendCommand("USER", true, request.getUser(), String.valueOf(request.getUserMode()), "*", request.getRealName());
            System.out.println("Sent inital commands");
        } catch (Throwable t) {
            disconnect(false);
            socket = null;
            socketInputStream = null;
            socketOutputStream = null;
            throw t;
        }

        // NOTE: Network thread
        // Everything below happens on this thread unless explicitly queued.
        Thread thread = new Thread(this::handleInput);
        thread.setName("IRC Connection Handler");
        thread.start();
    }

    @Override
    public void notifyMotdReceived() {
        super.notifyMotdReceived();
        getServerConnectionData().getCommandHandlerList().getHandler(NickCommandHandler.class).cancel(
                getServerConnectionData().getUserNick());
        if (connectCallback != null)
            connectCallback.onResponse(null);
        connectCallback = null;
        connectErrorCallback = null;
    }

    private void connectRequestNick(List<String> nickList, int index) throws IOException {
        getServerConnectionData().setUserNick(nickList.get(index));
        getServerConnectionData().getCommandHandlerList().getHandler(NickCommandHandler.class).onRequested(
                nickList.get(0), null, (String n, int i, String err) -> {
                    if (i == NickCommandHandler.ERR_NICKNAMEINUSE ||
                            i == NickCommandHandler.ERR_ERRONEUSNICKNAME) {
                        // Try next nickname
                        if (index + 1 >= nickList.size()) {
                            // Null the callback before calling it so the IOException that
                            // disconnect(false) triggers in handleInput() does not re-fire it.
                            ResponseErrorCallback cb = connectErrorCallback;
                            connectErrorCallback = null;
                            if (cb != null) cb.onError(new NickUnavailableException(
                                    err != null ? err : "No available nickname",
                                    new java.util.ArrayList<>(nickList)));
                            disconnect(false);
                            return;
                        }
                        try {
                            connectRequestNick(nickList, index + 1);
                        } catch (IOException e) {
                            if (connectErrorCallback != null)
                                connectErrorCallback.onError(new ChatApiException("Failed to request nickname"));
                        }
                    }
                });
        sendCommand(false, "NICK", false, nickList.get(index));
    }

    public interface DisconnectListener {
        void onDisconnected(IRCConnection connection, Exception reason);
    }

}
