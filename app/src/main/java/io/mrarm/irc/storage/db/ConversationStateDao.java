package io.mrarm.irc.storage.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.UUID;

@Dao
public interface ConversationStateDao {

    /* ---------- Reads ---------- */

    @Query("""
                SELECT *
                FROM conversation_state
                WHERE serverId = :serverId AND channel = :channel
            """)
    ConversationStateEntity get(UUID serverId, String channel);


    @Query("""
                SELECT COUNT(*)
                FROM messages_logs
                WHERE serverId = :serverId
                  AND channel = :channel
                  AND id > :lastReadId
            """)
    long getUnreadCount(UUID serverId, String channel, long lastReadId);

    @Query("""
                SELECT MIN(id)
                FROM messages_logs
                WHERE serverId = :serverId
                  AND channel = :channel
                  AND id > :lastReadId
            """)
    Long getFirstUnreadId(UUID serverId, String channel, long lastReadId);

    @Query("""
                SELECT MAX(id)
                FROM messages_logs
                WHERE serverId = :serverId
                  AND channel = :channel
            """)
    Long getLatestMessageId(UUID serverId, String channel);

    /* ---------- Writes ---------- */

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ConversationStateEntity entity);

    @Query("""
                INSERT OR IGNORE INTO conversation_state(serverId, channel, lastReadId, firstUnreadId, lastNotifiedId, mutedUntilMs)
                VALUES (:serverId, :channel, 0, 0, 0, 0)
            """)
    void ensureExists(UUID serverId, String channel);

    @Query("""
                UPDATE conversation_state
                SET lastReadId = CASE WHEN :lastReadId > lastReadId THEN :lastReadId ELSE lastReadId END,
                    firstUnreadId = 0
                WHERE serverId = :serverId
                  AND channel = :channel
            """)
    void markRead(UUID serverId, String channel, long lastReadId);

    @Query("""
                UPDATE conversation_state
                SET firstUnreadId = :messageId
                WHERE serverId = :serverId
                  AND channel = :channel
                  AND firstUnreadId = 0
            """)
    void setFirstUnreadIfEmpty(UUID serverId, String channel, long messageId);

    @Query("""
                UPDATE conversation_state
                SET lastNotifiedId = CASE WHEN :messageId > lastNotifiedId THEN :messageId ELSE lastNotifiedId END
                WHERE serverId = :serverId
                  AND channel = :channel
            """)
    void setLastNotified(UUID serverId, String channel, long messageId);


    /* ---------- Lifecycle ---------- */

    @Query("""
                DELETE FROM conversation_state
                WHERE serverId = :serverId
                  AND channel = :channel COLLATE NOCASE
            """)
    void deleteByChannel(UUID serverId, String channel);

    @Query("""
                DELETE FROM conversation_state
                WHERE serverId = :serverId
            """)
    void deleteByServer(UUID serverId);

    @Query("""
                DELETE FROM conversation_state
            """)
    void clear();
}
