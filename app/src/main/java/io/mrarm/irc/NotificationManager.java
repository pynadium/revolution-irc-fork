package io.mrarm.irc;

import android.app.NotificationChannelGroup;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import io.mrarm.irc.config.NotificationRule;
import io.mrarm.irc.config.NotificationRuleManager;
import io.mrarm.irc.connection.ServerConnectionManager;
import io.mrarm.irc.connection.ServerConnectionSession;
import io.mrarm.irc.protocol.ChatApi;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.irc.ServerConnectionApi;
import io.mrarm.irc.util.WarningHelper;

public class NotificationManager {

    public static final int CHAT_SUMMARY_NOTIFICATION_ID = 101;
    public static final String TAG = "NotificationManager";

    private static final String NOTIFICATION_CHANNEL_GROUP_SYSTEM = "01_system";
    private static final String NOTIFICATION_CHANNEL_GROUP_DEFAULT_RULES = "02_default";
    private static final String NOTIFICATION_CHANNEL_GROUP_USER_RULES = "03_user";
    public static final String NOTIFICATION_GROUP_CHAT = "chat";

    private static final NotificationManager sInstance = new NotificationManager();

    public static NotificationManager getInstance() {
        return sInstance;
    }

    private final List<UnreadMessageCountCallback> mGlobalUnreadCallbacks = new ArrayList<>();

    private String mLastSummaryChannel = null;

    public void processMessage(Context context, ServerConnectionSession connection, String channel,
                               MessageInfo info, MessageId messageId) {
        if (channel == null) {
            // No resolvable target channel for this event (e.g. a malformed/edge-case CTCP
            // reply) - getChannelManager(null, true) would otherwise create a permanent
            // phantom ChannelNotificationManager keyed by null that later broadcasts a null
            // channel to every other open fragment's listener.
            Log.w(TAG, "processMessage() dropping event with null channel: " + info.getType());
            return;
        }
        ChannelNotificationManager channelManager = connection.getNotificationManager().getChannelManager(channel, true);
        boolean isSelf = info.getSender() != null &&
                info.getSender().getNick().equals(connection.getUserNick());
        if (!isSelf && (info.getType() == MessageInfo.MessageType.NORMAL ||
                info.getType() == MessageInfo.MessageType.ME ||
                info.getType() == MessageInfo.MessageType.NOTICE))
            channelManager.addUnreadMessage(messageId);

        if (info.getMessage() == null || info.getSender() == null || isSelf)
            return;

        NotificationRule rule = findNotificationRule(connection, channel, info);
        if (rule == null || rule.settings.noNotification)
            return;
        synchronized (channelManager) {
            if (channelManager.addNotificationMessage(info, messageId)) {
                if (rule.settings.notificationChannelId == null)
                    ChannelNotificationManager.createChannel(context, rule);
                updateSummaryNotification(context, rule.settings.notificationChannelId);
                channelManager.showNotification(context, rule);
            }
        }
    }

    public boolean shouldMessageUseMentionFormatting(ServerConnectionSession connection,
                                                     String channel, MessageInfo info) {
        if (info.getMessage() == null || info.getSender() == null ||
                info.getSender().getNick().equals(connection.getUserNick()))
            return false;
        NotificationRule rule = findNotificationRule(connection, channel, info);
        return (rule != null && rule.settings.mentionFormatting);
    }

    public void clearAllNotifications(Context context, ServerConnectionSession connection) {
        ConnectionManager connectionData = connection.getNotificationManager();
        synchronized (connectionData.mChannels) {
            for (ChannelNotificationManager mgr : connectionData.mChannels.values())
                mgr.cancelNotification(context);
            connectionData.mChannels.clear();
        }
        updateSummaryNotification(context, null);
    }

    private NotificationRule findNotificationRule(ServerConnectionSession connection, String channel,
                                                  MessageInfo message) {
        ChatApi api = connection.getApiInstance();
        if (api instanceof ServerConnectionApi && channel != null && !channel.isEmpty() &&
                !((ServerConnectionApi) api).getServerConnectionData().getSupportList()
                        .getSupportedChannelTypes().contains(channel.charAt(0)))
            channel = null;

        ConnectionManager connData = connection.getNotificationManager();
        for (NotificationRule rule : NotificationRuleManager.getDefaultTopRules()) {
            if (rule.appliesTo(connData, channel, message) && rule.settings.enabled)
                return rule;
        }
        for (NotificationRule rule : NotificationRuleManager.getUserRules(connection.getConnectionManager().getContext())) {
            if (rule.appliesTo(connData, channel, message) && rule.settings.enabled)
                return rule;
        }
        for (NotificationRule rule : NotificationRuleManager.getDefaultBottomRules()) {
            if (rule.appliesTo(connData, channel, message) && rule.settings.enabled)
                return rule;
        }
        return null;
    }

    public void onNotificationDismissed(ServerConnectionSession connection,
                                        String channel) {
        ChannelNotificationManager channelManager = connection.getNotificationManager()
                .getChannelManager(channel, false);
        if (channelManager != null)
            channelManager.onNotificationDismissed();
    }


    public void updateSummaryNotification(Context context, String channel) {
        ChannelNotificationManager first = null;
        boolean isLong = false;
        int notificationCount = 0;
        StringBuilder longBuilder = new StringBuilder();
        for (ServerConnectionSession info : ServerConnectionManager.getInstance(context).getConnections()) {
            ConnectionManager cm = info.getNotificationManager();
            synchronized (cm.mChannels) {
                for (ChannelNotificationManager channelManager : cm.mChannels.values()) {
                    if (channelManager.getNotificationMessageCount() == 0)
                        continue;
                    if (first == null) {
                        first = channelManager;
                    } else {
                        longBuilder.append(context.getString(R.string.text_comma));
                        isLong = true;
                    }
                    longBuilder.append(channelManager.getChannel());
                    notificationCount++;
                }
            }
        }
        // Remove the notification if no notification entries were found
        if (first == null) {
            NotificationManagerCompat.from(context).cancel(CHAT_SUMMARY_NOTIFICATION_ID);
            return;
        }
        if (channel != null)
            mLastSummaryChannel = channel;
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context,
                mLastSummaryChannel);
        PendingIntent dismissIntent = PendingIntent.getBroadcast(context,
                ChannelNotificationManager.CHAT_DISMISS_INTENT_ID_START + CHAT_SUMMARY_NOTIFICATION_ID,
                ChannelNotificationManager.NotificationActionReceiver.getDismissIntentForSummary(context),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_notification_message)
                .setGroup(NOTIFICATION_GROUP_CHAT)
                .setGroupSummary(true)
                .setColor(context.getResources().getColor(R.color.colorNotificationMention, context.getTheme()))
                .setDeleteIntent(dismissIntent)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN);
        if (isLong) {
            PendingIntent intent = PendingIntent.getActivity(context, CHAT_SUMMARY_NOTIFICATION_ID,
                    MainActivity.getLaunchIntent(context, null, null),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notification
                    .setContentTitle(context.getResources().getQuantityString(R.plurals.notify_multiple_messages, notificationCount, notificationCount))
                    .setContentText(longBuilder.toString())
                    .setContentIntent(intent);
        } else {
            PendingIntent intent = PendingIntent.getActivity(context, CHAT_SUMMARY_NOTIFICATION_ID,
                    MainActivity.getLaunchIntent(context, first.getConnection(), first.getChannel()),
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            notification
                    .setContentTitle(first.getChannel())
                    .setContentText(first.getNotificationMessage(first.getNotificationMessageCount() - 1).getNotificationText(context))
                    .setContentIntent(intent);
        }
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        NotificationManagerCompat.from(context).notify(CHAT_SUMMARY_NOTIFICATION_ID, notification.build());
    }


    public void addGlobalUnreadMessageCountCallback(UnreadMessageCountCallback callback) {
        synchronized (mGlobalUnreadCallbacks) {
            mGlobalUnreadCallbacks.add(callback);
        }
    }

    public void removeGlobalUnreadMessageCountCallback(UnreadMessageCountCallback callback) {
        synchronized (mGlobalUnreadCallbacks) {
            mGlobalUnreadCallbacks.remove(callback);
        }
    }

    public void callUnreadMessageCountCallbacks(ServerConnectionSession connection, String channel,
                                                int messageCount, int oldMessageCount) {
        synchronized (mGlobalUnreadCallbacks) {
            for (UnreadMessageCountCallback cb : mGlobalUnreadCallbacks) {
                cb.onUnreadMessageCountChanged(connection, channel, messageCount, oldMessageCount);
            }
        }
        connection.getNotificationManager().callUnreadMessageCountCallbacks(channel, messageCount,
                oldMessageCount);
    }


    private static void createChannelGroup(Context ctx, String id, CharSequence name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        NotificationChannelGroup group = new NotificationChannelGroup(id, name);
        android.app.NotificationManager mgr = (android.app.NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        mgr.createNotificationChannelGroup(group);
    }

    private static boolean sChannelsCreated = false;

    private static void createChannelGroups(Context ctx) {
        if (sChannelsCreated)
            return;
        sChannelsCreated = true;

        createChannelGroup(ctx, NOTIFICATION_CHANNEL_GROUP_SYSTEM,
                ctx.getString(R.string.notification_channel_group_system));
        createChannelGroup(ctx, NOTIFICATION_CHANNEL_GROUP_DEFAULT_RULES,
                ctx.getString(R.string.notification_channel_group_default));
        createChannelGroup(ctx, NOTIFICATION_CHANNEL_GROUP_USER_RULES,
                ctx.getString(R.string.notification_channel_group_user));
    }

    public static String getSystemNotificationChannelGroup(Context ctx) {
        Log.d(TAG, "getSystemNotificationChannelGroup");
        createChannelGroups(ctx);
        return NOTIFICATION_CHANNEL_GROUP_SYSTEM;
    }

    public static String getDefaultNotificationChannelGroup(Context ctx) {
        createChannelGroups(ctx);
        return NOTIFICATION_CHANNEL_GROUP_DEFAULT_RULES;
    }

    public static String getUserNotificationChannelGroup(Context ctx) {
        createChannelGroups(ctx);
        return NOTIFICATION_CHANNEL_GROUP_USER_RULES;
    }

    public static void createDefaultChannels(Context context) {
        IRCService.createNotificationChannel(context);
        WarningHelper.getNotificationChannel(context);
        for (NotificationRule rule : NotificationRuleManager.getDefaultTopRules())
            ChannelNotificationManager.createChannel(context, rule);
        for (NotificationRule rule : NotificationRuleManager.getDefaultBottomRules())
            ChannelNotificationManager.createChannel(context, rule);
    }

    public static class ConnectionManager {

        private final ServerConnectionSession mConnection;
        private final Map<NotificationRule, Pattern> mCompiledPatterns = new HashMap<>();
        private final Map<String, ChannelNotificationManager> mChannels = new HashMap<>();
        private final List<UnreadMessageCountCallback> mUnreadCallbacks = new ArrayList<>();

        public ConnectionManager(ServerConnectionSession connection) {
            mConnection = connection;
        }

        public ServerConnectionSession getConnection() {
            return mConnection;
        }

        public Map<NotificationRule, Pattern> getCompiledPatterns() {
            return mCompiledPatterns;
        }

        public ChannelNotificationManager getChannelManager(String channel, boolean create) {
            synchronized (mChannels) {
                ChannelNotificationManager ret = mChannels.get(channel);
                if (ret == null && create) {
                    ret = new ChannelNotificationManager(mConnection, channel);
                    mChannels.put(channel, ret);
                    ret.initUnreadState();
                }
                return ret;
            }
        }

        public UUID getServerUUID() {
            return mConnection.getUUID();
        }

        public void addUnreadMessageCountCallback(UnreadMessageCountCallback callback) {
            synchronized (mUnreadCallbacks) {
                mUnreadCallbacks.add(callback);
            }
        }

        public void removeUnreadMessageCountCallback(UnreadMessageCountCallback callback) {
            synchronized (mUnreadCallbacks) {
                mUnreadCallbacks.remove(callback);
            }
        }

        private void callUnreadMessageCountCallbacks(String channel, int messageCount,
                                                     int oldMessageCount) {
            synchronized (mUnreadCallbacks) {
                for (UnreadMessageCountCallback cb : mUnreadCallbacks) {
                    cb.onUnreadMessageCountChanged(mConnection, channel, messageCount,
                            oldMessageCount);
                }
            }
        }

    }

    public interface UnreadMessageCountCallback {

        void onUnreadMessageCountChanged(ServerConnectionSession info, String channel,
                                         int messageCount, int oldMessageCount);

    }


}
