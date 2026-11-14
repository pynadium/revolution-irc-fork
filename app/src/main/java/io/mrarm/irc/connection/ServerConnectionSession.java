package io.mrarm.irc.connection;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.UserOverrideTrustManager;
import io.mrarm.irc.chat.ChatUIData;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.conversation.ConversationRepository;
import io.mrarm.irc.infrastructure.threading.DelayScheduler;
import io.mrarm.irc.message.MessagePipeline;
import io.mrarm.irc.message.MessageSink;
import io.mrarm.irc.protocol.ChannelListListener;
import io.mrarm.irc.protocol.ChatApi;
import io.mrarm.irc.protocol.NickUnavailableException;
import io.mrarm.irc.protocol.NoSuchChannelException;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.irc.IRCConnection;
import io.mrarm.irc.protocol.irc.IRCConnectionRequest;
import io.mrarm.irc.protocol.irc.ServerConnectionApi;
import io.mrarm.irc.protocol.irc.ServerConnectionData;
import io.mrarm.irc.protocol.irc.cap.SASLOptions;
import io.mrarm.irc.util.UserAutoRunCommandHelper;

/**
 * ServerConnectionInfo
 * <p>
 * Represents a single live IRC connection session.
 *
 * <p>This class owns the complete lifecycle and runtime state of one server
 * connection, including connection attempts, reconnect behavior, protocol
 * integration, and UI-facing state.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Execute connection lifecycle operations (connect, disconnect, reconnect)</li>
 *   <li>React to network connectivity changes</li>
 *   <li>Maintain per-connection state (connected, connecting, channels, flags)</li>
 *   <li>Coordinate reconnect execution using {@link io.mrarm.irc.connection.ReconnectPolicy}
 *       and {@link io.mrarm.irc.infrastructure.threading.DelayScheduler}</li>
 *   <li>Coordinate IRC protocol integration (delegated to {@link SessionInitializer})</li>
 *   <li>Bridge runtime events to UI and notification layers</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <p>This is intentionally a stateful, long-lived runtime object.
 * It is NOT a pure data model.</p>
 *
 * <p>Application-level concerns such as how many connections exist,
 * service lifecycle management, and persistence are delegated to
 * {@link ServerConnectionManager}.</p>
 *
 * <p>This class currently acts as a connection session / aggregate root.
 * Future refactors may split protocol wiring, UI state, and notification
 * coordination into smaller components.</p>
 */

public class ServerConnectionSession {

    private static final long CONNECTIVITY_LOSS_GRACE_MS = 3000L;

    private ServerConnectionManager mManager;
    private final SessionInitializer sessionInitializer;
    private final ServerConfigData mServerConfig;
    private List<String> mChannels;
    private ChatApi mApi;
    private final IRCConnectionRequest mConnectionRequest;
    private final SASLOptions mSASLOptions;
    private boolean mExpandedInDrawer = true;
    private boolean mConnected = false;
    private boolean mConnecting = false;
    private boolean mDisconnecting = false;
    private boolean mUserDisconnectRequest = false;
    private boolean mNickExhausted = false;
    private String mNickExhaustedReason = null;
    private List<String> mNickExhaustedNicks = null;
    private long mReconnectQueueTime = -1L;
    private ReconnectPolicy mReconnectPolicy;
    private final DelayScheduler mReconnectScheduler;
    private final NotificationManager.ConnectionManager mNotificationData;
    private UserAutoRunCommandHelper mAutoRunHelper;
    private final List<InfoChangeListener> mInfoListeners = new ArrayList<>();
    private final List<ChannelListChangeListener> mChannelsListeners = new ArrayList<>();
    private int mCurrentReconnectAttempt = -1;
    private final ChatUIData mChatUIData = new ChatUIData();
    private ConversationRepository mConversationRepository;

    private static final String TAG = "SERVER CONNECTION SESSION";

    public ServerConnectionSession(ServerConnectionManager manager,
                                   SessionInitializer initializer,
                                   ServerConfigData config,
                                   IRCConnectionRequest connectionRequest,
                                   SASLOptions saslOptions,
                                   List<String> joinChannels,
                                   DelayScheduler reconnectScheduler,
                                   ReconnectPolicy reconnectPolicy) {
        mManager = manager;
        sessionInitializer = initializer;
        mServerConfig = config;
        mConnectionRequest = connectionRequest;
        mSASLOptions = saslOptions;
        mNotificationData = new NotificationManager.ConnectionManager(this);
        mChannels = joinChannels;
        mReconnectScheduler = reconnectScheduler;
        mReconnectPolicy = reconnectPolicy;
        if (mChannels != null)
            mChannels.sort(String::compareToIgnoreCase);
    }

    private void setApi(ChatApi api) {
        synchronized (this) {
            mApi = api;
            api.getJoinedChannelList(this::setChannels, null);
            api.subscribeChannelList(new ChannelListListener() {
                @Override
                public void onChannelListChanged(List<String> list) {
                    setChannels(list);
                }

                @Override
                public void onChannelJoined(String s) {
                }

                @Override
                public void onChannelLeft(String s) {
                }
            }, null, null);
            mChatUIData.attachToConnection(api);
        }
    }

    public ServerConnectionManager getConnectionManager() {
        return mManager;
    }

    public void connect() {
        synchronized (this) {
            if (mDisconnecting)
                throw new RuntimeException("Trying to connect with mDisconnecting set");

            if (mConnected || mConnecting)
                return;

            mConnecting = true;
            mUserDisconnectRequest = false;
            mNickExhausted = false;
            mNickExhaustedReason = null;
            mNickExhaustedNicks = null;
            mReconnectQueueTime = -1L;
        }
        Log.i(TAG, "Connecting...");

        IRCConnection connection;
        boolean createdNewConnection = false;

        if (mApi == null || !(mApi instanceof IRCConnection)) {
            connection = new IRCConnection();
            sessionInitializer.attach(connection, this, mServerConfig, mSASLOptions);

            createdNewConnection = true;

        } else {
            connection = (IRCConnection) mApi;
        }

        IRCConnection fConnection = connection;

        List<String> rejoinChannels = getChannels();

        connection.connect(mConnectionRequest, (Void v) -> {
            // notifyMotdReceived() fires on the socket read thread. Post all state mutation
            // and side effects (listener notifications, IRCService.start, joinChannels) to
            // the main thread so that InfoChangeListeners are never called from the socket thread.
            mReconnectScheduler.schedule(0, () -> {
                synchronized (this) {
                    mConnecting = false;
                    mConnected = true;
                    mCurrentReconnectAttempt = 0;

                    if (mServerConfig.execCommandsConnected != null) {
                        if (mAutoRunHelper == null)
                            mAutoRunHelper = new UserAutoRunCommandHelper(this);
                        mAutoRunHelper.executeUserCommands(mServerConfig.execCommandsConnected);
                    }
                }
                // Notify listeners outside the lock — same rationale as notifyDisconnected().
                notifyInfoChanged();

                List<String> joinChannels = new ArrayList<>();
                if (mServerConfig.autojoinChannels != null)
                    joinChannels.addAll(mServerConfig.autojoinChannels);
                if (rejoinChannels != null && mServerConfig.rejoinChannels)
                    joinChannels.addAll(rejoinChannels);
                if (!joinChannels.isEmpty())
                    fConnection.joinChannels(joinChannels, null, null);
            });

        }, (Exception e) -> {
            // May arrive on the socket thread or the send executor. Always post to main.
            if (e instanceof UserOverrideTrustManager.UserRejectedCertificateException ||
                    (e.getCause() != null && e.getCause() instanceof
                            UserOverrideTrustManager.UserRejectedCertificateException)) {
                Log.d(TAG, "connect() -> Exception: User rejected the certificate", e);
                synchronized (this) {
                    mUserDisconnectRequest = true;
                }
            } else if (e instanceof NickUnavailableException) {
                // All configured nicks rejected — retrying with the same list is futile.
                // Stop auto-reconnect and surface the server's error to the UI.
                Log.d(TAG, "connect() -> Exception: all nicks exhausted, stopping reconnect", e);
                synchronized (this) {
                    mUserDisconnectRequest = true;
                    mNickExhausted = true;
                    mNickExhaustedReason = e.getMessage();
                    mNickExhaustedNicks = ((NickUnavailableException) e).getTriedNicks();
                }
            }
            Log.d(TAG, "connect() -> Exception: posting notifyDisconnected to main thread", e);
            mReconnectScheduler.schedule(0, this::notifyDisconnected);
        });

        if (createdNewConnection) {
            setApi(connection);
        }
    }

    private void disconnect(boolean userExecutedQuit) {
        synchronized (this) {
            mUserDisconnectRequest = true;
            mReconnectScheduler.cancel(mReconnectRunnable);
            if (!isConnected() && isConnecting()) {
                mConnecting = false;
                mDisconnecting = true;
                Thread disconnectThread = new Thread(() -> ((IRCConnection) getApiInstance()).disconnect(true));
                disconnectThread.setName("Disconnect Thread");
                disconnectThread.start();
            } else if (isConnected()) {
                mDisconnecting = true;
                String message = AppSettings.getDefaultQuitMessage();
                if (userExecutedQuit) {
                    ((IRCConnection) mApi).disconnect(null, null);
                } else {
                    mApi.quit(message, null, (Exception e) -> ((IRCConnection) getApiInstance()).disconnect(true));
                }
            } else {
                notifyFullyDisconnected();
            }
        }
    }

    public void disconnect() {
        disconnect(false);
    }

    public void notifyUserExecutedQuit() {
        disconnect(true);
    }

    public void notifyDisconnected() {
        Log.i(TAG, "notifyDisconnect() invoked");
        synchronized (this) {
            if (mAutoRunHelper != null)
                mAutoRunHelper.cancelUserCommandExecution();
        }

        boolean fullyDisconnected;
        boolean userRequested;
        synchronized (this) {
            // Write state directly — do NOT call setConnected() here, as that would invoke
            // notifyInfoChanged() while holding this lock, creating a lock-ordering hazard
            // (this → mInfoListeners). notifyInfoChanged() is called below, outside the lock.
            mConnected = false;
            mConnecting = false;
            fullyDisconnected = mDisconnecting;
            userRequested = mUserDisconnectRequest;
        }

        notifyInfoChanged();

        if (fullyDisconnected) {
            Log.i(TAG, "notifyDisconnect(): mDisconnecting=true, calling notifyFullyDisconnected()");
            notifyFullyDisconnected();
            return;
        }
        if (userRequested) {
            Log.i(TAG, "notifyDisconnect(): mUserDisconnectRequest=true, skipping reconnect");
            return;
        }

        int reconnectDelay = mReconnectPolicy.getReconnectDelay(mCurrentReconnectAttempt++);
        if (reconnectDelay == -1)
            return;
        Log.i(TAG, "Queuing reconnect in " + reconnectDelay + " ms");
        mReconnectQueueTime = System.nanoTime();
        mReconnectScheduler.schedule(reconnectDelay, mReconnectRunnable);
    }

    private void notifyFullyDisconnected() {
        synchronized (this) {
            mConnected = false;
            mConnecting = false;
            mDisconnecting = false;
        }
        // notifyInfoChanged() called outside the lock — see notifyDisconnected() for rationale.
        // (notifyDisconnected already called notifyInfoChanged once before reaching here;
        //  this second call propagates the mDisconnecting=false state change.)
        notifyInfoChanged();
        mManager.notifyConnectionFullyDisconnected(this);
    }

    public synchronized void close() {
        Log.i(TAG, "close()");
        if (getApiInstance() != null) {
            ServerConnectionData connectionData = ((ServerConnectionApi) getApiInstance())
                    .getServerConnectionData();

            MessageSink sink = connectionData.getMessageSink();
            if (sink instanceof MessagePipeline) {
                ((MessagePipeline) sink).shutdown();
            }

            connectionData.setChannelDataStorage(null);
        }
    }

    private final Runnable mConnectivityLossRunnable = () -> disconnectForReconnect();


    private void disconnectForReconnect() {
        synchronized (this) {
            mReconnectScheduler.cancel(mReconnectRunnable);
            if (!mConnected && !mConnecting)
                return;
            mDisconnecting = true;
            // do NOT set mUserDisconnectRequest = true
        }
        if (mConnecting) {
            Thread t = new Thread(() -> ((IRCConnection) getApiInstance()).disconnect(true));
            t.setName("Disconnect Thread");
            t.start();
        } else {
            String message = AppSettings.getDefaultQuitMessage();
            mApi.quit(message, null, (Exception e) -> ((IRCConnection) getApiInstance()).disconnect(true));
        }
    }

    public void notifyConnectivityChanged(boolean hasAnyConnectivity, boolean hasWifi) {
        Log.i(TAG, "notifyConnectivityChanged(), " +
                "hasAnyConnectivity: " + hasAnyConnectivity +
                "hasWifi: " + hasWifi);

        mReconnectScheduler.cancel(mReconnectRunnable);
        mReconnectScheduler.cancel(mConnectivityLossRunnable);

        if (!hasAnyConnectivity) {
            mReconnectScheduler.schedule(CONNECTIVITY_LOSS_GRACE_MS, mConnectivityLossRunnable);
            return;
        }

        if (!AppSettings.isReconnectEnabled() ||
                (AppSettings.isReconnectWiFiOnly() && !hasWifi))
            return;

        if (AppSettings.isReconnectOnConnectivityChangeEnabled()) {
            connect(); // this will be ignored if we are already connected

        } else if (mReconnectQueueTime != -1L) {
            long reconnectDelay = mReconnectPolicy.getReconnectDelay(mCurrentReconnectAttempt++);
            if (reconnectDelay == -1)
                return;
            reconnectDelay = reconnectDelay - (System.nanoTime() - mReconnectQueueTime) / 1000000L;
            if (reconnectDelay <= 0L)
                connect();
            else
                mReconnectScheduler.schedule(reconnectDelay, mReconnectRunnable);
        }
    }

    public UUID getUUID() {
        return mServerConfig.uuid;
    }

    public String getName() {
        return mServerConfig.name;
    }

    public synchronized ChatApi getApiInstance() {
        return mApi;
    }


    public MessageId.Parser getMessageIdParser() {
        return ((ServerConnectionApi) getApiInstance())
                .getServerConnectionData()
                .getMessageIdParser();
    }

    public boolean isConnected() {
        synchronized (this) {
            return mConnected;
        }
    }

    public void setConnected(boolean connected) {
        synchronized (this) {
            mConnected = connected;
        }
        notifyInfoChanged();
    }

    public boolean isConnecting() {
        synchronized (this) {
            return mConnecting;
        }
    }

    public boolean isDisconnecting() {
        synchronized (this) {
            return mDisconnecting;
        }
    }

    public boolean hasUserDisconnectRequest() {
        synchronized (this) {
            return mUserDisconnectRequest;
        }
    }

    public boolean isNickExhausted() {
        synchronized (this) {
            return mNickExhausted;
        }
    }

    public String getNickExhaustedReason() {
        synchronized (this) {
            return mNickExhaustedReason;
        }
    }

    public List<String> getNickExhaustedNicks() {
        synchronized (this) {
            return mNickExhaustedNicks;
        }
    }

    public void reconnectWithNick(String nick) {
        synchronized (this) {
            mNickExhausted = false;
            mNickExhaustedReason = null;
            mNickExhaustedNicks = null;
            mUserDisconnectRequest = false;
            List<String> list = new ArrayList<>();
            list.add(nick);
            mConnectionRequest.setNickList(list);
        }
        connect();
    }

    public List<String> getChannels() {
        synchronized (this) {
            return mChannels;
        }
    }

    // Cosmetic-only original-case form of `channel` (which stays canonical/lowercase-for-DM
    // for storage and lookups - see ChannelData.getDisplayName()). Falls back to `channel`
    // itself if the channel isn't currently joined.
    public String getChannelDisplayName(String channel) {
        ChatApi api = getApiInstance();
        if (!(api instanceof ServerConnectionApi))
            return channel;
        try {
            return ((ServerConnectionApi) api).getChannelData(channel).getDisplayName();
        } catch (NoSuchChannelException e) {
            return channel;
        }
    }

    public boolean hasChannel(String channel) {
        synchronized (this) {
            for (String c : mChannels) {
                if (c.equalsIgnoreCase(channel))
                    return true;
            }
            return false;
        }
    }

    public void setChannels(List<String> channels) {
        channels.sort(String::compareToIgnoreCase);
        synchronized (this) {
            mChannels = channels;
        }
        synchronized (mChannelsListeners) {
            mManager.notifyChannelListChanged(this, channels);
            mManager.saveAutoconnectListAsync();
            List<ChannelListChangeListener> listeners = new ArrayList<>(mChannelsListeners);
            for (ChannelListChangeListener listener : listeners)
                listener.onChannelListChanged(this, channels);
        }
    }

    public boolean isExpandedInDrawer() {
        synchronized (this) {
            return mExpandedInDrawer;
        }
    }

    public void setExpandedInDrawer(boolean expanded) {
        synchronized (this) {
            mExpandedInDrawer = expanded;
        }
    }

    public NotificationManager.ConnectionManager getNotificationManager() {
        return mNotificationData;
    }

    public String getUserNick() {
        return ((ServerConnectionApi) getApiInstance()).getServerConnectionData().getUserNick();
    }

    public ChatUIData getChatUIData() {
        return mChatUIData;
    }

    public void addOnChannelInfoChangeListener(InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.add(listener);
        }
    }

    public void removeOnChannelInfoChangeListener(InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.remove(listener);
        }
    }

    public void addOnChannelListChangeListener(ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.add(listener);
        }
    }

    public void removeOnChannelListChangeListener(ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.remove(listener);
        }
    }

    private void notifyInfoChanged() {
        synchronized (mInfoListeners) {
            for (InfoChangeListener listener : mInfoListeners)
                listener.onConnectionInfoChanged(this);
            mManager.notifyConnectionInfoChanged(this);
        }
    }

    private final Runnable mReconnectRunnable = () -> {
        mReconnectQueueTime = -1L;
        if (!AppSettings.isReconnectEnabled() || (AppSettings.isReconnectWiFiOnly() &&
                !ServerConnectionManager.isWifiConnected(mManager.getContext())))
            return;
        this.connect();
    };

    public ConversationRepository getmConversationRepository() {
        return mConversationRepository;
    }

    public void setmConversationRepository(ConversationRepository repo) {
        this.mConversationRepository = repo;
    }

    public interface InfoChangeListener {
        void onConnectionInfoChanged(ServerConnectionSession connection);
    }

    public interface ChannelListChangeListener {
        void onChannelListChanged(ServerConnectionSession connection, List<String> newChannels);
    }

}
