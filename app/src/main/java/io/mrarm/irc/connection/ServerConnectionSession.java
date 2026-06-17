package io.mrarm.irc.connection;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.UserOverrideTrustManager;
import io.mrarm.irc.chat.ChatUIData;
import io.mrarm.irc.chatlib.ChannelListListener;
import io.mrarm.irc.chatlib.ChatApi;
import io.mrarm.irc.chatlib.dto.MessageId;
import io.mrarm.irc.chatlib.irc.IRCConnection;
import io.mrarm.irc.chatlib.irc.IRCConnectionRequest;
import io.mrarm.irc.chatlib.irc.ServerConnectionApi;
import io.mrarm.irc.chatlib.irc.ServerConnectionData;
import io.mrarm.irc.chatlib.irc.cap.SASLOptions;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.infrastructure.threading.DelayScheduler;
import io.mrarm.irc.message.MessagePipeline;
import io.mrarm.irc.message.MessageSink;
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
    private long mReconnectQueueTime = -1L;
    private ReconnectPolicy mReconnectPolicy;
    private final DelayScheduler mReconnectScheduler;
    private final NotificationManager.ConnectionManager mNotificationData;
    private UserAutoRunCommandHelper mAutoRunHelper;
    private final List<InfoChangeListener> mInfoListeners = new ArrayList<>();
    private final List<ChannelListChangeListener> mChannelsListeners = new ArrayList<>();
    private int mCurrentReconnectAttempt = -1;
    private final ChatUIData mChatUIData = new ChatUIData();

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
                    setConnected(true);
                    mCurrentReconnectAttempt = 0;

                    if (mServerConfig.execCommandsConnected != null) {
                        if (mAutoRunHelper == null)
                            mAutoRunHelper = new UserAutoRunCommandHelper(this);
                        mAutoRunHelper.executeUserCommands(mServerConfig.execCommandsConnected);
                    }
                }

                List<String> joinChannels = new ArrayList<>();
                if (mServerConfig.autojoinChannels != null)
                    joinChannels.addAll(mServerConfig.autojoinChannels);
                if (rejoinChannels != null && mServerConfig.rejoinChannels)
                    joinChannels.addAll(rejoinChannels);
                if (!joinChannels.isEmpty())
                    fConnection.joinChannels(joinChannels, null, null);
            });

        }, (Exception e) -> {
            if (e instanceof UserOverrideTrustManager.UserRejectedCertificateException ||
                    (e.getCause() != null && e.getCause() instanceof
                            UserOverrideTrustManager.UserRejectedCertificateException)) {
                Log.d(TAG, "connect() -> Exception catched: User rejected the certificate", e);
                synchronized (this) {
                    mUserDisconnectRequest = true;
                }
            }
            Log.d(TAG, "connect() -> Exception catched: notifyingDisconnection", e);
            notifyDisconnected();
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
        // Do not check isDisconnecting() outside the lock — mDisconnecting can be set by
        // disconnect() on another thread between an unlocked check and the synchronized block
        // below, producing a TOCTOU. The in-lock check on mDisconnecting is sufficient.
        synchronized (this) {
            setConnected(false);
            mConnecting = false;
            if (mDisconnecting) {
                    Log.i(TAG, "notifyDisconnect(): mDisconnecting then notifyFullDisconnected()");
                    notifyFullyDisconnected();
                    return;
            }
            if (mUserDisconnectRequest) {
                Log.i(TAG, "notifyDisconnect(): mUserDisconnectRequest then return");
                return;
            }
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
            setConnected(false);
            mConnecting = false;
            mDisconnecting = false;
        }
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

    public List<String> getChannels() {
        synchronized (this) {
            return mChannels;
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

    public interface InfoChangeListener {
        void onConnectionInfoChanged(ServerConnectionSession connection);
    }

    public interface ChannelListChangeListener {
        void onChannelListChanged(ServerConnectionSession connection, List<String> newChannels);
    }

}
