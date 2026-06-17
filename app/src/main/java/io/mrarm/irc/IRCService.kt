package io.mrarm.irc

import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.mrarm.irc.connection.NetworkUtility
import io.mrarm.irc.connection.ServerConnectionManager
import io.mrarm.irc.job.ServerPingScheduler
import io.mrarm.irc.util.WarningHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * IRCService
 *
 * Core background service responsible for maintaining and monitoring IRC connections,
 * receiving messages, and posting notifications. Runs as a foreground service to ensure
 * persistence even when the app is not in the foreground.
 */
@Keep
class IRCService : LifecycleService() {

    private var createdChannel = false
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var networkUpdateJob: Job? = null

    /** Called when the service is first created. Initializes managers and listeners. */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "oncreate() called")

        WarningHelper.setAppContext(applicationContext)
        registerNetworkCallback()
        ServerPingScheduler.getInstance(this).startIfEnabled()
    }

    /** Cleans up connections, listeners, and schedulers when the service is destroyed. */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
        unregisterNetworkCallback()
        ServerPingScheduler.getInstance(this).stop()
    }

    /**
     * Handles start commands, including initializing the foreground notification.
     * Keeps track of connected/connecting/disconnected servers to display in the status.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: started")
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: return START_STICKY
        Log.d(TAG, "onStartCommand: action=$action")
        if (action == ACTION_START_FOREGROUND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !createdChannel) {
                createNotificationChannel(this)
                createdChannel = true
                Log.d(TAG, "onStartCommand: created Notification Channel")
            }


            // Compose notification text summarizing connection states
            val manager = ServerConnectionManager.getInstance(this)
            val (connectedCount, connectingCount, disconnectedCount) =
                manager.connections.fold(Triple(0, 0, 0)) { acc, conn ->
                    when {
                        conn.isConnected -> acc.copy(first = acc.first + 1)
                        conn.isConnecting -> acc.copy(second = acc.second + 1)
                        else -> acc.copy(third = acc.third + 1)
                    }
                }

            val builder = StringBuilder().apply {
                append(
                    resources.getQuantityString(
                        R.plurals.service_status_connected,
                        connectedCount,
                        connectedCount
                    )
                )
                if (connectingCount > 0) append(getString(R.string.text_comma))
                    .append(
                        resources.getQuantityString(
                            R.plurals.service_status_connecting,
                            connectingCount,
                            connectingCount
                        )
                    )
                if (disconnectedCount > 0) append(getString(R.string.text_comma))
                    .append(
                        resources.getQuantityString(
                            R.plurals.service_status_disconnected,
                            disconnectedCount,
                            disconnectedCount
                        )
                    )
            }
            Log.d(TAG, "onStartCommand: composed notification text summaries with connection states $builder")


            // Build persistent foreground notification
            val mainIntent = MainActivity.getLaunchIntent(this, null, null)
            val exitIntent = PendingIntent.getBroadcast(
                this,
                EXIT_ACTION_ID,
                ExitActionReceiver.getIntent(this),
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, IDLE_NOTIFICATION_CHANNEL)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(builder.toString())
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        IDLE_NOTIFICATION_ID,
                        mainIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .addAction(
                    R.drawable.ic_close,
                    getString(R.string.action_exit),
                    exitIntent
                )
            notification.setSmallIcon(R.drawable.ic_server_connected)

            startForeground(IDLE_NOTIFICATION_ID, notification.build())
            Log.d(TAG, "onStartCommand: Built persistent foreground notification")

        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /** Registers a system callback to monitor network connectivity changes. */
    private fun registerNetworkCallback() {
        Log.d(TAG, "registerNetworkCallback: registering a system callback to monitor connectivity changes")

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager?
        val manager = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "registerNetworkCallback: onAvailable network")
                handleNetworkUpdate()
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "registerNetworkCallback: onLost network")
                handleNetworkUpdate()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                Log.d(TAG, "registerNetworkCallback: onCapabilitiesChanged network")
                handleNetworkUpdate()
            }
        }
        networkCallback = callback
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            Log.d(TAG, "registerNetworkCallback: registered Network Callbacks")
            handleNetworkUpdate()
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to register network callback", e)
        }
    }

    /** Unregisters the previously installed network callback. */
    private fun unregisterNetworkCallback() {
        val manager = connectivityManager ?: return
        val callback = networkCallback ?: return
        try {
            manager.unregisterNetworkCallback(callback)
            Log.d(TAG, "unregisterNetworkCallback: unregistered Network Callbacks")
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    /** Called when network status changes; notifies managers to update connectivity. */
    private fun handleNetworkUpdate() {
        networkUpdateJob?.cancel()
        networkUpdateJob = lifecycleScope.launch {
            delay(300L)
            Log.d(TAG, "handleNetworkUpdate")
            val manager = ServerConnectionManager.getInstance(this@IRCService)
            manager.notifyConnectivityChanged(NetworkUtility.hasAnyNetworkCapability(this@IRCService))
            val wifiConnected = ServerConnectionManager.isWifiConnected(this@IRCService)
            ServerPingScheduler.getInstance(this@IRCService)
                .onWifiStateChanged(wifiConnected)
        }
    }


    /** BootReceiver automatically starts the service after device reboot. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
                Log.d(TAG, "onReceive: BootReceiver automatically starts the service after device reboot")
                start(context)
            }
        }
    }

    @Keep
    class ExitActionReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            // Verify that the broadcast is the expected internal action
            if (intent.action == ACTION_EXIT) {
                (context.applicationContext as? IRCApplication)?.requestExit()
            }
        }

        companion object {
            private const val ACTION_EXIT = "io.mrarm.irc.ACTION_EXIT"

            /** Builds a properly configured Intent for this receiver. */
            @JvmStatic
            fun getIntent(context: Context): Intent =
                Intent(context, ExitActionReceiver::class.java).setAction(ACTION_EXIT)
        }
    }

    companion object {
        private const val TAG = "IRCService"

        const val IDLE_NOTIFICATION_ID = 100
        const val EXIT_ACTION_ID = 102
        const val ACTION_START_FOREGROUND = "start_foreground"

        private const val IDLE_NOTIFICATION_CHANNEL = "IdleNotification"

        @JvmStatic
        fun start(context: Context) {
            Log.i(TAG, "start: Starting service...")
            val intent = Intent(context, IRCService::class.java).apply {
                action = ACTION_START_FOREGROUND
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            Log.i(TAG, "stop: Stopping service...")
            context.stopService(Intent(context, IRCService::class.java))
        }

        @JvmStatic
        fun createNotificationChannel(ctx: Context) {
            Log.d(TAG, "createNotificationChannel")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O){
                Log.d(TAG, "createNotificationChannel Build.VERSION.SDK_INT < Build.VERSION_CODE.0 = true")
                return
            }
            val channel: NotificationChannel = NotificationChannel(
                IDLE_NOTIFICATION_CHANNEL,
                ctx.getString(R.string.notification_channel_idle),
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply {
                Log.d(TAG, "createNotificationChannel .apply{}")
                group = NotificationManager.getSystemNotificationChannelGroup(ctx)
                setShowBadge(false)
            }
            val mgr = ctx.getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            mgr.createNotificationChannel(channel)
            Log.d(TAG, "createNotificationChannel created NotificationChannel ${channel.description}")
        }
    }
}
