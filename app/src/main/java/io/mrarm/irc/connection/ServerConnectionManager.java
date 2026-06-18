package io.mrarm.irc.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.IRCService;
import io.mrarm.irc.NotificationManager;
import io.mrarm.irc.R;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.infrastructure.threading.DelayScheduler;
import io.mrarm.irc.infrastructure.threading.ManagedCoroutineScope;
import io.mrarm.irc.infrastructure.threading.SchedulerProvider;
import io.mrarm.irc.infrastructure.threading.SchedulerProviderHolder;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;

/**
 * ServerConnectionManager
 *
 * Application-level orchestrator for server connections.
 *
 * <p>This class manages the lifecycle of {@link ServerConnectionSession} instances
 * from the perspective of the application, NOT the IRC protocol.</p>
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Maintain the collection of active server connections</li>
 *   <li>Create and remove connections via {@link ServerConnectionFactory}</li>
 *   <li>Restore managed connections on app startup using
 *       {@link io.mrarm.irc.connection.ServerConnectionRegistry}</li>
 *   <li>Coordinate global side effects (foreground service start/stop)</li>
 *   <li>Broadcast application-wide connection and channel change events</li>
 *   <li>Handle bulk operations (disconnect all, destroy on app exit)</li>
 * </ul>
 *
 * <h3>Non-responsibilities</h3>
 * <ul>
 *   <li>Does NOT manage IRC protocol details</li>
 *   <li>Does NOT execute connection or reconnect logic</li>
 *   <li>Does NOT schedule reconnect timers</li>
 *   <li>Does NOT own per-connection state</li>
 * </ul>
 *
 * <p>Each {@link ServerConnectionSession} instance is responsible for managing
 * its own connection lifecycle (connect, disconnect, reconnect) and runtime state.</p>
 *
 * <p>In short: this class orchestrates <b>which</b> connections exist and
 * <b>when</b> the application should react, while individual connections
 * decide <b>how</b> to behave.</p>
 */

public class ServerConnectionManager {

    private static ServerConnectionManager instance;
    private final static String TAG = "[SERVER CONNECTION MANAGER]";

    private final Context mContext;
    private final HashMap<UUID, ServerConnectionSession> mConnectionsMap = new HashMap<>();
    private final ArrayList<ServerConnectionSession> mConnections = new ArrayList<>();
    private final HashMap<UUID, ServerConnectionSession> mDisconnectingConnections = new HashMap<>();
    private final List<ConnectionsListener> mListeners = new ArrayList<>();
    private final List<ServerConnectionSession.ChannelListChangeListener> mChannelsListeners = new ArrayList<>();
    private final List<ServerConnectionSession.InfoChangeListener> mInfoListeners = new ArrayList<>();
    private boolean mDestroying = false;
    private final ManagedCoroutineScope mIoScopeWrapper;
    private final CoroutineScope mIoScope;
    private final ServerConnectionFactory connectionFactory;
    public final ServerConnectionRegistry mConnectionRegistry;


    public static boolean hasInstance() {
        return instance != null;
    }

    public static synchronized ServerConnectionManager getInstance(Context context) {
        if (instance == null && context != null)
            instance = new ServerConnectionManager(context.getApplicationContext());
        return instance;
    }

    public static synchronized void destroyInstance() {
        if (instance == null)
            return;
        instance.mDestroying = true;
        while (!instance.mConnections.isEmpty()) {
            ServerConnectionSession connection = instance.mConnections.get(instance.mConnections.size() - 1);
            connection.disconnect();
            instance.removeConnection(connection, false);
            instance.killDisconnectingConnection(connection.getUUID());
        }
        instance.mIoScopeWrapper.cancel();
        instance = null;
    }

    public ServerConnectionManager(Context context) {
        this(context, null);
    }

    public ServerConnectionManager(Context context, SchedulerProvider schedulerProvider) {
        Log.i(TAG, "Called constructor");

        mContext = context;
        SchedulerProvider resolvedProvider = schedulerProvider != null ? schedulerProvider : SchedulerProviderHolder.get();
        mIoScopeWrapper = new ManagedCoroutineScope(resolvedProvider.getIoDispatcher());
        mIoScope = mIoScopeWrapper.getScope();
        DelayScheduler mReconnectScheduler = resolvedProvider.getReconnectionScheduler();
        connectionFactory = new ServerConnectionFactory(mContext, mReconnectScheduler);
        mConnectionRegistry = new ServerConnectionRegistry(mContext);

        List<ServerConnectionRegistry.ConnectedServerEntry> entries = mConnectionRegistry.loadConnectedServers();
        ServerConfigManager configManager = ServerConfigManager.getInstance(context);

        for (ServerConnectionRegistry.ConnectedServerEntry entry : entries) {
            ServerConfigData configData = configManager.findServer(entry.uuid);
            if (configData != null) {
                try {
                    createConnection(configData, entry.channels, false);
                } catch (NickNotSetException ignored) {
                }
            }
        }
        Log.i(TAG, "Restored " + entries.size() + " connections");
    }

    void saveAutoconnectListAsync() {
        BuildersKt.launch(mIoScope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT, (coroutineScope, continuation) -> {
            saveRegistry();
            return Unit.INSTANCE;
        });
    }

    private void saveRegistry() {
        List<ServerConnectionRegistry.ConnectedServerEntry> entries = new ArrayList<>();
        for (ServerConnectionSession connection : getConnections()) {
            ServerConnectionRegistry.ConnectedServerEntry e = new ServerConnectionRegistry.ConnectedServerEntry();
            e.uuid = connection.getUUID();
            e.channels = connection.getChannels();
            entries.add(e);
        }
        mConnectionRegistry.saveConnectedServers(entries);
    }

    public Context getContext() {
        return mContext;
    }

    public List<ServerConnectionSession> getConnections() {
        synchronized (this) {
            return new ArrayList<>(mConnections);
        }
    }

    public void addConnection(ServerConnectionSession connection, boolean saveAutoconnect) {
        synchronized (this) {
            if (mConnectionsMap.containsKey(connection.getUUID()))
                throw new RuntimeException("A connection with this UUID already exists");
            mConnectionsMap.put(connection.getUUID(), connection);
            mConnections.add(connection);
            if (saveAutoconnect)
                saveAutoconnectListAsync();
        }
        synchronized (mListeners) {
            for (ConnectionsListener listener : mListeners)
                listener.onConnectionAdded(connection);
        }
        IRCService.start(mContext);
    }

    public void addConnection(ServerConnectionSession connection) {
        addConnection(connection, true);
    }

    private ServerConnectionSession createConnection(ServerConfigData data,
                                                     List<String> joinChannels,
                                                     boolean saveAutoconnect) {
        killDisconnectingConnection(data.uuid);

        ServerConnectionSession connectionInfo = connectionFactory.create(this, data, joinChannels);

        connectionInfo.connect();
        addConnection(connectionInfo, saveAutoconnect);
        return connectionInfo;
    }

    public ServerConnectionSession createConnection(ServerConfigData data) {
        return createConnection(data, null, true);
    }

    public void tryCreateConnection(ServerConfigData data, Context activity) {
        if (ServerConnectionManager.getInstance(getContext()).hasConnection(data.uuid))
            return;

        if (!NetworkUtility.hasAnyNetworkCapability(this.mContext)) {
            Toast.makeText(activity, R.string.connection_error_no_network, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            createConnection(data);
        } catch (NickNotSetException e) {
            Toast.makeText(activity, R.string.connection_error_no_nick, Toast.LENGTH_SHORT).show();
        }
    }

    public void removeConnection(ServerConnectionSession connection, boolean saveAutoconnect) {
        NotificationManager.getInstance().clearAllNotifications(mContext, connection);
        synchronized (this) {
            synchronized (connection) {
                if (connection.isDisconnecting()) {
                    synchronized (mDisconnectingConnections) {
                        if (mDisconnectingConnections.containsKey(connection.getUUID()))
                            throw new RuntimeException("mDisconnectingConnections already contains a disconnecting connection with this UUID");
                        mDisconnectingConnections.put(connection.getUUID(), connection);
                    }
                } else if (connection.isConnecting() || connection.isConnected() || !connection.hasUserDisconnectRequest()) {
                    throw new RuntimeException("Trying to remove a non-disconnected connection");
                } else {
                    connection.close();
                }
            }
            mConnections.remove(connection);
            mConnectionsMap.remove(connection.getUUID());
            if (saveAutoconnect)
                saveAutoconnectListAsync();
            if (mConnections.isEmpty())
                IRCService.stop(mContext);
            else if (!mDestroying)
                IRCService.start(mContext); // update connection count
        }
        synchronized (mListeners) {
            for (ConnectionsListener listener : mListeners)
                listener.onConnectionRemoved(connection);
        }
    }

    public void removeConnection(ServerConnectionSession connection) {
        removeConnection(connection, true);
    }

    /**
     * Stop keeping track of a disconnected connection. A call to this function is required if you
     * want to do something with this server's logs to make sure they are properly released.
     */
    public void killDisconnectingConnection(UUID uuid) {
        synchronized (mDisconnectingConnections) {
            ServerConnectionSession connection = mDisconnectingConnections.get(uuid);
            if (connection == null)
                return;
            connection.close();
            mDisconnectingConnections.remove(uuid);
        }
    }

    public void disconnectAndRemoveAllConnections(boolean kill) {
        synchronized (this) {
            while (!mConnections.isEmpty()) {
                ServerConnectionSession connection = mConnections.get(mConnections.size() - 1);
                connection.disconnect();
                removeConnection(connection, false);
                if (kill)
                    killDisconnectingConnection(connection.getUUID());
            }
            saveAutoconnectListAsync();
        }
    }

    public ServerConnectionSession getConnection(UUID uuid) {
        synchronized (this) {
            return mConnectionsMap.get(uuid);
        }
    }

    public boolean hasConnection(UUID uuid) {
        synchronized (this) {
            return mConnectionsMap.containsKey(uuid);
        }
    }


    public void addListener(ConnectionsListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    public void removeListener(ConnectionsListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    public void addGlobalConnectionInfoListener(ServerConnectionSession.InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.add(listener);
        }
    }

    public void removeGlobalConnectionInfoListener(ServerConnectionSession.InfoChangeListener listener) {
        synchronized (mInfoListeners) {
            mInfoListeners.remove(listener);
        }
    }

    public void addGlobalChannelListListener(ServerConnectionSession.ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.add(listener);
        }
    }

    public void removeGlobalChannelListListener(ServerConnectionSession.ChannelListChangeListener listener) {
        synchronized (mChannelsListeners) {
            mChannelsListeners.remove(listener);
        }
    }

    void notifyConnectionInfoChanged(ServerConnectionSession connection) {
        if (!hasConnection(connection.getUUID()))
            return;
        synchronized (mInfoListeners) {
            for (ServerConnectionSession.InfoChangeListener listener : mInfoListeners)
                listener.onConnectionInfoChanged(connection);
            if (!mDestroying)
                IRCService.start(mContext);
        }
    }

    void notifyChannelListChanged(ServerConnectionSession connection, List<String> newChannels) {
        if (!hasConnection(connection.getUUID()))
            return;
        synchronized (mChannelsListeners) {
            for (ServerConnectionSession.ChannelListChangeListener listener : mChannelsListeners)
                listener.onChannelListChanged(connection, newChannels);
        }
    }

    void notifyConnectionFullyDisconnected(ServerConnectionSession connection) {
        ServerConnectionSession removed;
        synchronized (mDisconnectingConnections) {
            removed = mDisconnectingConnections.remove(connection.getUUID());
        }
        if (removed != null)
            connection.close();
    }

    public void notifyConnectivityChanged(boolean hasAnyConnectivity) {
        if (!hasAnyConnectivity && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.os.PowerManager pm = (android.os.PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isDeviceIdleMode()) {
                Log.i(TAG, "notifyConnectivityChanged: Doze active, suppressing proactive disconnect");
                return;
            }
        }
        boolean hasWifi = isWifiConnected(mContext);
        synchronized (this) {
            for (ServerConnectionSession server : mConnectionsMap.values())
                server.notifyConnectivityChanged(hasAnyConnectivity, hasWifi);
        }
    }

    public static boolean isWifiConnected(Context context) {
        ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (mgr == null)
            return false;
        Network network = mgr.getActiveNetwork();
        if (network == null)
            return false;
        NetworkCapabilities capabilities = mgr.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public interface ConnectionsListener {

        void onConnectionAdded(ServerConnectionSession connection);

        void onConnectionRemoved(ServerConnectionSession connection);

    }

    public static class NickNotSetException extends RuntimeException {
    }
}
