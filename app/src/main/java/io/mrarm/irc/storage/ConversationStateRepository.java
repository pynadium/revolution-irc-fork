package io.mrarm.irc.storage;

import android.content.Context;

import java.util.UUID;

import io.mrarm.irc.storage.db.ChatLogDatabase;
import io.mrarm.irc.storage.db.ConversationStateDao;
import io.mrarm.irc.storage.db.ConversationStateEntity;

public class ConversationStateRepository {

    private final Context context;

    public ConversationStateRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    // Resolved on every use, never cached: a restore closes and replaces chatlogs.db.
    private ChatLogDatabase db() {
        return ChatLogDatabase.getInstance(context);
    }

    private ConversationStateDao dao() {
        return db().conversationStateDao();
    }

    public ConversationStateEntity getState(UUID serverId, String channel) {
        final ConversationStateEntity[] out = new ConversationStateEntity[1];

        ConversationStateDao dao = dao();
        db().runInTransaction(() -> {
            dao.ensureExists(serverId, channel);
            out[0] = dao.get(serverId, channel);
        });

        return out[0];
    }

    public void onUnreadMessageArrived(UUID serverId, String channel, long messageId) {
        ConversationStateDao dao = dao();
        dao.ensureExists(serverId, channel);
        dao.setFirstUnreadIfEmpty(serverId, channel, messageId);
    }

    public void markConversationRead(UUID serverId, String channel, long lastReadId) {
        ConversationStateDao dao = dao();
        dao.ensureExists(serverId, channel);
        dao.markRead(serverId, channel, lastReadId);
    }

    public void markNotified(UUID serverId, String channel, long messageId) {
        ConversationStateDao dao = dao();
        dao.ensureExists(serverId, channel);
        dao.setLastNotified(serverId, channel, messageId);
    }

    public long getUnreadCount(UUID serverId, String channel) {
        ConversationStateEntity state = getState(serverId, channel);
        if (state == null) {
            // State not ready yet; treat as unread-from-start
            return dao().getUnreadCount(serverId, channel, 0);
        }
        return dao().getUnreadCount(serverId, channel, state.lastReadId);
    }
}
