package io.mrarm.irc.chatlib.irc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import io.mrarm.irc.chatlib.ChannelListListener;
import io.mrarm.irc.chatlib.NoSuchChannelException;
import io.mrarm.irc.chatlib.dto.MessageId;
import io.mrarm.irc.chatlib.dto.MessageInfo;
import io.mrarm.irc.chatlib.irc.cap.CapabilityManager;
import io.mrarm.irc.chatlib.user.WritableUserInfoApi;
import io.mrarm.irc.message.MessageBus;
import io.mrarm.irc.message.MessageSink;
import io.mrarm.irc.storage.MessageStorageRepository;

// Primary session state (ServerConnectionData + ChannelData) is mutated on the network read thread via command handlers.
public class ServerConnectionData {

    // Pure logical IRC state
    private String userNick;
    private String userUser;
    private String userHost;
    private final HashMap<String, ChannelData> joinedChannels = new HashMap<>();
    private UUID serverUUID;
    private ServerStatusData serverStatusData = new ServerStatusData();
    private final ServerSupportList supportList = new ServerSupportList();
    private ChannelDataStorage channelDataStorage;

    // Domain behavior component - It defines how the session reacts to protocol input
    private CommandHandlerList commandHandlerList = new CommandHandlerList();

    // Coordinator/Observer infrastructure
    private final List<ChannelListListener> channelListListeners = new ArrayList<>();

    // Domain Infrastructure
    private CapabilityManager capabilityManager = new CapabilityManager(this);

    // Infrastructure
    private ServerConnectionApi api;
    private WritableUserInfoApi userInfoApi;
    private MessageStorageRepository messageStorageRepository;
    private NickPrefixParser nickPrefixParser = OneCharNickPrefixParser.getInstance();
    private final MessageFilterList messageFilterList = new MessageFilterList();
    private MessageSink messageSink;
    private MessageBus messageBus;
    private MessageId.Parser messageIdParser;

    public void setMessageIdParser(MessageId.Parser p) { messageIdParser = p; }

    public MessageId.Parser getMessageIdParser() { return messageIdParser; }

    public ServerConnectionData() {
        commandHandlerList.addDefaultHandlers();
        capabilityManager.addDefaultCapabilities();
    }

    public synchronized MessageSink getMessageSink() {
        return messageSink;
    }

    public synchronized void setMessageSink(MessageSink messageSink) {
        this.messageSink = messageSink;
    }

    public MessageBus getMessageBus() {
        return messageBus;
    }

    public void setMessageBus(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    public synchronized void setUserNick(String nick) {
        userNick = nick;
    }

    public synchronized String getUserNick() {
        return userNick;
    }

    public synchronized void setUserExtraInfo(String user, String host) {
        userUser = user;
        userHost = host;
    }

    public synchronized String getUserUser() {
        return userUser;
    }

    public synchronized String getUserHost() {
        return userHost;
    }

    public ServerConnectionApi getApi() {
        return api;
    }

    public void setApi(ServerConnectionApi api) {
        this.api = api;
    }

    public synchronized void setServerUUID(UUID uuid) {
        this.serverUUID = uuid;
    }

    public synchronized UUID getServerUUID() {
        return serverUUID;
    }

    public synchronized WritableUserInfoApi getUserInfoApi() {
        return userInfoApi;
    }

    public synchronized void setUserInfoApi(WritableUserInfoApi api) {
        this.userInfoApi = api;
    }

    public synchronized ChannelDataStorage getChannelDataStorage() {
        return channelDataStorage;
    }

    public synchronized void setChannelDataStorage(ChannelDataStorage channelDataStorage) {
        this.channelDataStorage = channelDataStorage;
    }

    public synchronized void setMessageStorageRepository(MessageStorageRepository repo) {
        this.messageStorageRepository = repo;
    }

    public synchronized MessageStorageRepository getMessageStorageRepository() {
        return messageStorageRepository;
    }

    public NickPrefixParser getNickPrefixParser() {
        return nickPrefixParser;
    }

    public void setNickPrefixParser(NickPrefixParser parser) {
        this.nickPrefixParser = parser;
    }

    public ServerSupportList getSupportList() {
        return supportList;
    }

    public CommandHandlerList getCommandHandlerList() {
        return commandHandlerList;
    }

    public CapabilityManager getCapabilityManager() {
        return capabilityManager;
    }

    public MessageFilterList getMessageFilterList() {
        return messageFilterList;
    }

    public ChannelData getJoinedChannelData(String channelName) throws NoSuchChannelException {
        String lChannelName = channelName.toLowerCase();
        synchronized (joinedChannels) {
            if (!joinedChannels.containsKey(lChannelName))
                throw new NoSuchChannelException();
            return joinedChannels.get(lChannelName);
        }
    }

    public List<String> getJoinedChannelList() {
        synchronized (joinedChannels) {
            ArrayList<String> list = new ArrayList<>();
            for (ChannelData cdata : joinedChannels.values())
                list.add(cdata.getName());
            return list;
        }
    }

    public void onChannelJoined(String channelName) {
        String lChannelName = channelName.toLowerCase();
        // DM targets (no channel-type prefix, e.g. a bare nick) are canonicalized to
        // lowercase here too - not just in MessageCommandHandler's incoming path - so a
        // DM opened via /msg (or any other join entry point) always lands on the same
        // ChannelData/messages_logs/conversation_state row as one opened by an incoming
        // message, regardless of which path created it first or what casing was used.
        boolean isDirectMessage = !channelName.isEmpty()
                && !supportList.getSupportedChannelTypes().contains(channelName.charAt(0));
        String canonicalName = isDirectMessage ? lChannelName : channelName;
        synchronized (joinedChannels) {
            if (joinedChannels.containsKey(lChannelName))
                return;
            ChannelData data = new ChannelData(this, canonicalName);
            if (isDirectMessage)
                data.setDisplayName(channelName);
            data.loadFromStoredData();
            joinedChannels.put(lChannelName, data);
        }
        synchronized (channelListListeners) {
            if (channelListListeners.size() > 0) {
                List<String> joinedChannels = getJoinedChannelList();
                for (ChannelListListener listener : channelListListeners) {
                    listener.onChannelJoined(channelName);
                    listener.onChannelListChanged(joinedChannels);
                }
            }
        }
    }

    public void onChannelLeft(String channelName) {
        String lChannelName = channelName.toLowerCase();
        synchronized (joinedChannels) {
            if (!joinedChannels.containsKey(lChannelName))
                return;
            joinedChannels.remove(lChannelName);
        }
        synchronized (channelListListeners) {
            if (channelListListeners.size() > 0) {
                List<String> joinedChannels = getJoinedChannelList();
                for (ChannelListListener listener : channelListListeners) {
                    listener.onChannelLeft(channelName);
                    listener.onChannelListChanged(joinedChannels);
                }
            }
        }
    }

    void reset() {
        synchronized (joinedChannels) {
            joinedChannels.clear();
        }
        getCapabilityManager().reset();
        try {
            getUserInfoApi().clearAllUsersChannelPresences(null, null).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public void addLocalMessageToAllChannels(MessageInfo messageInfo) {
        if (messageSink != null) {
            messageSink.accept(null, messageInfo);
        }
    }

    public ServerStatusData getServerStatusData() {
        return serverStatusData;
    }

    public void subscribeChannelList(ChannelListListener listener) {
        channelListListeners.add(listener);
    }

    public void unsubscribeChannelList(ChannelListListener listener) {
        channelListListeners.remove(listener);
    }
}
