package io.mrarm.irc.chatlib.irc.handlers;

import android.util.Log;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import io.mrarm.irc.chatlib.NoSuchChannelException;
import io.mrarm.irc.chatlib.dto.MessageInfo;
import io.mrarm.irc.chatlib.dto.StatusMessageInfo;
import io.mrarm.irc.chatlib.irc.ChannelData;
import io.mrarm.irc.chatlib.irc.CommandHandler;
import io.mrarm.irc.chatlib.irc.InvalidMessageException;
import io.mrarm.irc.chatlib.irc.MessagePrefix;
import io.mrarm.irc.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.chatlib.irc.dcc.DCCClientManager;
import io.mrarm.irc.chatlib.irc.dcc.DCCServerManager;
import io.mrarm.irc.chatlib.irc.dcc.DCCUtils;


public class MessageCommandHandler implements CommandHandler {

    private long ctcpLastReplySeconds = 0L;
    private int ctcpSecondReplyCount = 0;
    private String ctcpVersionReply = "Chatlib:unknown:unknown";
    private DCCServerManager dccServerManager;
    private DCCClientManager dccClientManager;
    private final String TAG = "[MESSAGE COMMAND HANDLER]";

    @Override
    public Object[] getHandledCommands() {
        return new Object[]{"PRIVMSG", "NOTICE"};
    }

    public void setCtcpVersionReply(String str) {
        ctcpVersionReply = str;
    }

    public void setCtcpVersionReply(String clientName, String clientVersion, String system) {
        setCtcpVersionReply(clientName + ":" + clientVersion + ":" + system);
    }

    public void setDCCServerManager(DCCServerManager dccServerManager) {
        this.dccServerManager = dccServerManager;
    }

    public void setDCCClientManager(DCCClientManager dccClientManager) {
        this.dccClientManager = dccClientManager;
    }

    // This is the first semantic message point after parsing.
    @Override
    public void handle(ServerConnectionData connection, MessagePrefix sender, String command, List<String> params,
                       Map<String, String> tags)
            throws InvalidMessageException {
        Log.d(TAG, "handle() " + command + " " + sender);
        try {
            MessageInfo.MessageType type = (command.equals("NOTICE") ? MessageInfo.MessageType.NOTICE :
                    MessageInfo.MessageType.NORMAL);
            UUID userUUID = null;
            if (sender != null)
                // resolveUserSync is safe to call on the socket read thread: it is guaranteed
                // to run inline with no I/O or thread-switching.
            {
                userUUID = connection.getUserInfoApi().resolveUserSync(
                        sender.getNick(), sender.getUser(), sender.getHost());
                Log.d(TAG, "handle() has resolved userUUID: " + userUUID +
                        "nick: " + sender.getNick() +
                        "user: " + sender.getUser() +
                        "host: " + sender.getHost());
            }

            String[] targetChannels = CommandHandler.getParamWithCheck(params, 0).split(",");

            // NOTE CTCP handling: pure protocol logic
            // Side effects: ServerStatusData.addMessage(...) - connection.getApi().sendNotice(...)
            String text = CommandHandler.getParamWithCheck(params, 1);
            if (text.indexOf('\20') != -1)
                text = lowDequote(text);
            int ctcpS = text.indexOf('\01');
            int ctcpE = text.lastIndexOf('\01');
            if (ctcpS != -1 && ctcpE != -1 && sender != null) {
                for (String ctcpCommand : text.substring(ctcpS, ctcpE).split("\01"))
                    processCtcp(
                            connection,
                            sender,
                            userUUID,
                            targetChannels,
                            ctcpCommand.indexOf('\134') == -1 ? ctcpCommand : ctcpDequote(ctcpCommand),
                            type == MessageInfo.MessageType.NOTICE,
                            tags);
                if (ctcpS == 0 && ctcpE == text.length() - 1)
                    return;
                text = text.substring(0, ctcpS) + text.substring(ctcpE + 1, text.length());
            }

            // NOTE Channel resolution (conversation logic mixed in)
            // Architectural violation: Conversation state mutation happening inside protocol handler
            for (String channel : targetChannels) {
                ChannelData channelData = null;
                try {
                    channelData = connection.getJoinedChannelData(channel);
                } catch (NoSuchChannelException ignored) {
                }
                if (sender == null || (channelData == null && sender.getUser() == null && sender.getHost() == null)) {
                    connection.getServerStatusData().addMessage(new StatusMessageInfo(sender != null ?
                            sender.getServerName() : null, new Date(), StatusMessageInfo.MessageType.NOTICE, text));
                    continue;
                }

                if (channel.equalsIgnoreCase(connection.getUserNick()) || channelData == null) {
                    channelData = getChannelData(connection, sender, channel);
                    if (channelData == null)
                        continue;
                }
                // NOTE: Message dispatch: at this point protocol layer hands off to ChannelData
                // -> everything downstream is no longer protocol
                // Message creation (domain object)
                channelData.addMessage(new MessageInfo.Builder(sender.toSenderInfo(userUUID, channelData), text, type), tags);
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void processCtcp(ServerConnectionData connection, MessagePrefix sender, UUID userUUID, String[] targetChannels, String data, boolean notice, Map<String, String> tags) throws InterruptedException, ExecutionException, InvalidMessageException {
        Log.d(TAG, "processCtcp() " + data);
        int iof = data.indexOf(' ');
        String command = iof == -1 ? data : data.substring(0, iof);
        String args = data.substring(iof + 1);
        if (command.equals("ACTION")) {
            Log.d(TAG, "processCtcp() " + command);
            for (String channel : targetChannels) {
                ChannelData channelData = getChannelData(connection, sender, channel);
                if (channelData == null)
                    continue;
                channelData.addMessage(new MessageInfo.Builder(sender.toSenderInfo(userUUID, channelData), args, MessageInfo.MessageType.ME), tags);
            }
        } else if (command.equals("PING") && !notice) {
            Log.d(TAG, "processCtcp() " + command);
            if (!rateLimitCtcpCommand() || args.length() > 32)
                return;
            for (int i = 0; i < args.length(); i++) {
                if (args.charAt(i) < ' ')
                    return;
            }
            connection.getServerStatusData().addMessage(new StatusMessageInfo(sender.getNick(), new Date(), StatusMessageInfo.MessageType.CTCP_PING, null));
            connection.getApi().sendNotice(sender.getNick(), "\01PING " + args + "\01", null, null);
        } else if (command.equals("VERSION") && !notice) {
            Log.d(TAG, "processCtcp() " + command);
            if (!rateLimitCtcpCommand())
                return;
            connection.getServerStatusData().addMessage(new StatusMessageInfo(sender.getNick(), new Date(), StatusMessageInfo.MessageType.CTCP_VERSION, null));
            connection.getApi().sendNotice(sender.getNick(), "\01VERSION " + ctcpVersionReply + "\01", null, null);
        } else if (command.equals("DCC")) {
            Log.d(TAG, "processCtcp() " + command);
            if (args.startsWith("RESUME ") && dccServerManager != null && rateLimitCtcpCommand()) {
                args = args.substring(7);
                int filenameLen = DCCUtils.getFilenameLength(args);
                String filename = args.substring(0, filenameLen);
                String[] otherArgs = args.substring(filenameLen + (filenameLen < args.length() && args.charAt(filenameLen) == ' ' ? 1 : 0)).split(" ");
                int port = -1;
                long offset = -1;
                try {
                    port = Integer.parseInt(otherArgs[0]);
                    offset = Long.parseLong(otherArgs[1]);
                } catch (Exception ignored) { // NumberFormatException or NPE
                    throw new InvalidMessageException("DCC RESUME: invalid numeric values");
                }
                if (offset < 0)
                    throw new InvalidMessageException("DCC RESUME: offset must be non-negative");

                if (dccServerManager.continueUpload(connection, sender.getNick(), filename, port, offset)) {
                    connection.getApi().sendMessage(sender.getNick(), "\01DCC ACCEPT " + filename + " " +
                            otherArgs[0] + " " + otherArgs[1] + "\01", null, null);
                }
            }
            if (args.startsWith("SEND ") && dccClientManager != null) {
                args = args.substring(5);
                int filenameLen = DCCUtils.getFilenameLength(args);
                String filename = args.substring(0, filenameLen);
                String[] otherArgs = args.substring(filenameLen + (filenameLen < args.length() && args.charAt(filenameLen) == ' ' ? 1 : 0)).split(" ");
                String ip = null;
                int port = -1;
                long size = -1;
                int reverseId = -1;
                try {
                    ip = DCCUtils.convertIPFromCommand(otherArgs[0]);
                    port = Integer.parseInt(otherArgs[1]);
                    size = Long.parseLong(otherArgs[2]);
                    if (otherArgs.length > 3)
                        reverseId = Integer.parseInt(otherArgs[3]);
                } catch (Exception ignored) { // NumberFormatException or NPE
                    throw new InvalidMessageException("DCC RESUME: invalid numeric values");
                }
                if (otherArgs.length > 3 && port == 0) { // Reverse DCC request
                    if (dccClientManager != null)
                        dccClientManager.onFileOfferedUsingReverse(connection, sender, filename, size, reverseId);
                    return;
                }
                if (otherArgs.length > 3) { // Reverse DCC response
                    if (dccServerManager != null) // no need to rate limit, as we limit the count of uploads in that part of code anyways
                        dccServerManager.handleReverseUploadResponse(connection, sender.getNick(), filename, reverseId,
                                ip, port);
                    return;
                }

                dccClientManager.onFileOffered(connection, sender, filename, ip, port, size);
            }
        }
        Log.d(TAG, "processCtcp() " + command + " UNHANDLED!");
        // TODO: Implement other CTCP commands
    }

    private boolean rateLimitCtcpCommand() {
        long t = System.currentTimeMillis() / 1000L;
        if (t != ctcpLastReplySeconds) {
            ctcpLastReplySeconds = t;
            ctcpSecondReplyCount = 1;
            return true;
        }
        return (++ctcpSecondReplyCount <= 3);
    }

    private ChannelData getChannelData(ServerConnectionData connection, MessagePrefix sender, String channel) {
        boolean isDirectMessage = (channel.equalsIgnoreCase(connection.getUserNick()) ||
                channel.equalsIgnoreCase(sender.getNick()));
        if (isDirectMessage)
            // Lowercased so the query's channel key stays identical across
            // reconnects and prefix-casing variance from the server, avoiding
            // duplicate ChannelData/ChannelNotificationManager/messages_logs
            // entries for what is the same conversation.
            channel = sender.getNick().toLowerCase();
        try {
            return connection.getJoinedChannelData(channel);
        } catch (NoSuchChannelException exception) {
            if (isDirectMessage || (!channel.isEmpty() &&
                    !connection.getSupportList().getSupportedChannelTypes().contains(channel.charAt(0)))) {
                connection.onChannelJoined(channel);
                try {
                    return connection.getJoinedChannelData(channel);
                } catch (NoSuchChannelException e) {
                    return null;
                }
            }
            return null;
        }
    }

    // http://www.irchelp.org/protocol/ctcpspec.html

    private String lowDequote(String text) {
        int len = text.length();
        StringBuilder outpBuilder = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\020' && i + 1 < len) {
                int cc = text.charAt(++i);
                switch (cc) {
                    case '0':
                        outpBuilder.append(0);
                        break;
                    case 'n':
                        outpBuilder.append('\n');
                        break;
                    case 'r':
                        outpBuilder.append('\r');
                        break;
                    case '\20':
                        outpBuilder.append('\20');
                        break;
                    default:
                        --i;
                }
            } else {
                outpBuilder.append(c);
            }
        }
        return outpBuilder.toString();
    }

    private String ctcpDequote(String text) {
        StringBuilder outpBuilder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\134') {
                if (i + 1 < text.length() && text.charAt(i + 1) == 'a') {
                    outpBuilder.append('\01');
                    i++;
                }
            } else {
                outpBuilder.append(c);
            }
        }
        return outpBuilder.toString();
    }

}
