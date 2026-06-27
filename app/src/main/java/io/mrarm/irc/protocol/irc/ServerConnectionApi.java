package io.mrarm.irc.protocol.irc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import io.mrarm.irc.protocol.ChannelInfoListener;
import io.mrarm.irc.protocol.ChannelListListener;
import io.mrarm.irc.protocol.ChatApi;
import io.mrarm.irc.protocol.NoSuchChannelException;
import io.mrarm.irc.protocol.ResponseCallback;
import io.mrarm.irc.protocol.ResponseErrorCallback;
import io.mrarm.irc.protocol.StatusMessageListener;
import io.mrarm.irc.protocol.dto.ChannelInfo;
import io.mrarm.irc.protocol.dto.ChannelList;
import io.mrarm.irc.protocol.dto.StatusMessageInfo;
import io.mrarm.irc.protocol.dto.StatusMessageList;
import io.mrarm.irc.protocol.irc.handlers.ListCommandHandler;
import io.mrarm.irc.protocol.user.UserInfoApi;
import io.mrarm.irc.protocol.util.SimpleRequestExecutor;

public abstract class ServerConnectionApi implements ChatApi {

    private ServerConnectionData serverConnectionData;

    public ServerConnectionApi(ServerConnectionData serverConnectionData) {
        this.serverConnectionData = serverConnectionData;
        serverConnectionData.setApi(this);
    }

    public ServerConnectionData getServerConnectionData() {
        return serverConnectionData;
    }

    @Override
    public UserInfoApi getUserInfoApi() {
        return serverConnectionData.getUserInfoApi();
    }


    public void notifyMotdReceived() {
        // stub
    }

    public abstract Future<Void> sendCommand(String command, boolean isLastArgFullLine, String[] args,
                                             ResponseCallback<Void> callback, ResponseErrorCallback errorCallback);

    /**
     * This is a internal function intended for capability and command handler usage only.
     */
    public abstract void sendCommand(String command, boolean isLastArgFullLine, String... args) throws IOException;


    public ChannelData getChannelData(String channelName) throws NoSuchChannelException {
        return serverConnectionData.getJoinedChannelData(channelName);
    }

    @Override
    public Future<List<String>> getJoinedChannelList(ResponseCallback<List<String>> callback,
                                                     ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            return serverConnectionData.getJoinedChannelList();
        }, callback, errorCallback);
    }

    @Override
    public Future<ChannelInfo> getChannelInfo(String channelName, ResponseCallback<ChannelInfo> callback,
                                              ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            ChannelData data = getChannelData(channelName);
            return new ChannelInfo(data.getName(), data.getTopic(), data.getTopicSetBy(), data.getTopicSetOn(),
                    data.getMembersAsNickPrefixList());
        }, callback, errorCallback);
    }

    @Override
    public Future<ChannelList> listChannels(ResponseCallback<ChannelList> callback,
                                            ResponseCallback<ChannelList.Entry> entryCallback,
                                            ResponseErrorCallback errorCallback) {
        return getServerConnectionData().getCommandHandlerList().getHandler(ListCommandHandler.class).addRequest(
                getServerConnectionData(), callback, entryCallback, errorCallback);
    }

    // TODO: This still isn't a deep clone of the message list, change it to one

    @Override
    public Future<StatusMessageList> getStatusMessages(int count, StatusMessageList after,
                                                       ResponseCallback<StatusMessageList> callback,
                                                       ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            List<StatusMessageInfo> messages = serverConnectionData.getServerStatusData().getMessages();
            List<StatusMessageInfo> ret = new ArrayList<>();
            synchronized (messages) {
                for (int i = Math.max(messages.size() - count, 0); i < messages.size(); i++)
                    ret.add(messages.get(i));
            }
            return new StatusMessageList(ret);
        }, callback, errorCallback);
    }

    public Future<Void> subscribeChannelList(ChannelListListener listener, ResponseCallback<Void> callback,
                                             ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getServerConnectionData().subscribeChannelList(listener);
            return null;
        }, callback, errorCallback);
    }

    public Future<Void> unsubscribeChannelList(ChannelListListener listener, ResponseCallback<Void> callback,
                                               ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getServerConnectionData().unsubscribeChannelList(listener);
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> subscribeChannelInfo(String channelName, ChannelInfoListener listener,
                                             ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getChannelData(channelName).subscribeInfo(listener);
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> unsubscribeChannelInfo(String channelName, ChannelInfoListener listener,
                                               ResponseCallback<Void> callback, ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getChannelData(channelName).unsubscribeInfo(listener);
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> subscribeStatusMessages(StatusMessageListener listener, ResponseCallback<Void> callback,
                                                ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getServerConnectionData().getServerStatusData().subscribeMessages(listener);
            return null;
        }, callback, errorCallback);
    }

    @Override
    public Future<Void> unsubscribeStatusMessages(StatusMessageListener listener, ResponseCallback<Void> callback,
                                                  ResponseErrorCallback errorCallback) {
        return SimpleRequestExecutor.run(() -> {
            getServerConnectionData().getServerStatusData().unsubscribeMessages(listener);
            return null;
        }, callback, errorCallback);
    }

}
