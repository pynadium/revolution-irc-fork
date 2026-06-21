package io.mrarm.irc.storage.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;
import java.util.UUID;

import io.mrarm.irc.storage.MessageStatsRepository;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(MessageEntity msg);

    /**
     * Load messages older than a certain ID (scroll up)
     */
    @Query("""
                SELECT * FROM messages_logs
                WHERE serverId = :serverId AND channel = :channel AND id < :beforeId
                AND type NOT IN (:excludeTypes)
                ORDER BY id DESC
                LIMIT :limit
            """)
    List<MessageEntity> loadBefore(UUID serverId, String channel, long beforeId, int limit,
                                    List<Integer> excludeTypes);

    /**
     * Load messages newer than a certain ID (scroll down)
     */
    @Query("""
                SELECT * FROM messages_logs
                WHERE serverId = :serverId AND channel = :channel AND id > :afterId
                AND type NOT IN (:excludeTypes)
                ORDER BY id ASC
                LIMIT :limit
            """)
    List<MessageEntity> loadAfter(UUID serverId, String channel, long afterId, int limit,
                                   List<Integer> excludeTypes);

    /**
     * Initial tail loadConnectedServers
     */
    @Query("""
                SELECT * FROM messages_logs
                WHERE serverId = :serverId AND channel = :channel
                AND type NOT IN (:excludeTypes)
                ORDER BY id DESC
                LIMIT :limit
            """)
    List<MessageEntity> loadRecent(UUID serverId, String channel, int limit,
                                    List<Integer> excludeTypes);

    /**
     * Find specific message
     */
    @Query("SELECT * FROM messages_logs WHERE id = :id LIMIT 1")
    MessageEntity findById(long id);

    /**
     * Log viewer: distinct filter values
     */
    @Query("SELECT DISTINCT serverId FROM messages_logs")
    List<UUID> getDistinctServerIds();

    // `channel` is the grouping/identity key - for DMs it's intentionally forced to lowercase
    // by ServerConnectionData.onChannelJoined for any conversation active after that fix, so
    // it no longer carries the contact's real casing. The real casing only survives in `sender`
    // on INCOMING rows (sender == the contact, case-preserved). MessageStorageRepository uses
    // this id to look up that row's `sender`/`channel` and pick whichever reflects the real nick.
    @Query("""
                SELECT MAX(id) FROM messages_logs
                WHERE serverId = :serverId
                GROUP BY channel COLLATE NOCASE
            """)
    List<Long> getMostRecentIdPerChannelGroup(UUID serverId);

    @Query("SELECT * FROM messages_logs WHERE id IN (:ids)")
    List<MessageEntity> findByIds(List<Long> ids);

    // Same "most recent real casing" pick as getDistinctChannels above, instead of MIN(sender).
    @Query("""
                SELECT sender FROM messages_logs
                WHERE serverId = :serverId
                AND (:channel IS NULL OR channel = :channel COLLATE NOCASE)
                AND sender IS NOT NULL
                AND id IN (
                    SELECT MAX(id) FROM messages_logs
                    WHERE serverId = :serverId
                    AND (:channel IS NULL OR channel = :channel COLLATE NOCASE)
                    AND sender IS NOT NULL
                    GROUP BY sender COLLATE NOCASE
                )
                ORDER BY sender COLLATE NOCASE
            """)
    List<String> getDistinctSenders(UUID serverId, String channel);

    /**
     * Log viewer: filtered scroll-up paging (channel/sender optional, matched case-insensitively
     * for the same reason as the distinct-value queries above)
     */
    @Query("""
                SELECT * FROM messages_logs
                WHERE serverId = :serverId
                AND (:channel IS NULL OR channel = :channel COLLATE NOCASE)
                AND (:sender IS NULL OR sender = :sender COLLATE NOCASE)
                AND id < :beforeId
                AND type NOT IN (:excludeTypes)
                ORDER BY id DESC
                LIMIT :limit
            """)
    List<MessageEntity> loadFilteredBefore(UUID serverId, String channel, String sender,
                                            long beforeId, int limit, List<Integer> excludeTypes);

    /**
     * Stats
     */
    @Query("SELECT SUM(aprox_row_size) FROM messages_logs")
    Long getGlobalUsage();

    @Query("SELECT SUM(aprox_row_size) FROM messages_logs WHERE serverId = :serverId")
    Long getUsageForServer(UUID serverId);

    @Query("SELECT COUNT(*) FROM messages_logs WHERE serverId = :serverId")
    Long getMessageCountForServer(UUID serverId);

    @Query("""
                SELECT serverId, SUM(aprox_row_size) AS size
                FROM messages_logs
                GROUP BY serverId
                ORDER BY size DESC
            """)
    List<MessageStatsRepository.ServerUsage> getUsageForAllServers();

    /**
     * Deletion
     */
    @Query("DELETE FROM messages_logs WHERE serverId = :serverId")
    void deleteByServer(UUID serverId);

    @Query("DELETE FROM messages_logs")
    void deleteAll();

    @Query("""
            UPDATE messages_logs
            SET
                text = NULL,
                sender = NULL,
                channel = 'null',
                timestamp = 0,
                extra_json = NULL
            WHERE serverId = :serverId;
            """)
    void replaceDataByServer(UUID serverId);

    @Query("""
            UPDATE messages_logs
            SET
                text = NULL,
                sender = NULL,
                channel = 'null',
                timestamp = 0,
                extra_json = NULL
            """)
    void replaceAll();

    @Query("""
                SELECT id,
                aprox_row_size AS aproxRowSize
                FROM messages_logs
                ORDER BY id ASC
                LIMIT :limit
            """)
    List<IdSizePair> selectOldestGlobal(int limit);

    @Query("""
                SELECT id,
                aprox_row_size AS aproxRowSize
                FROM messages_logs
                WHERE kind = :kind
                ORDER BY id ASC
                LIMIT :limit
            """)
    List<IdSizePair> selectOldestGlobalByKind(MessageKind kind, int limit);


    @Query("""
                SELECT id,
                aprox_row_size AS aproxRowSize
                FROM messages_logs
                WHERE serverId = :serverId
                ORDER BY id ASC
                LIMIT :limit
            """)
    List<IdSizePair> selectOldestForServer(UUID serverId, int limit);

    @Query("""
                SELECT id,
                aprox_row_size AS aproxRowSize
                FROM messages_logs
                WHERE serverId = :serverId AND kind = :kind
                ORDER BY id ASC
                LIMIT :limit
            """)
    List<IdSizePair> selectOldestForServerByKind(UUID serverId, MessageKind kind, int limit);

    @Query("""
                DELETE FROM messages_logs
                WHERE id IN (:ids)
            """)
    int deleteByIds(List<Long> ids);

    @Query("""
            SELECT id FROM messages_logs 
            WHERE dedupe_key = :dedupeKey 
            LIMIT 1 
            """
    )
    long findIdByDedupeKey(String dedupeKey);


    @Query("""
            UPDATE messages_logs
            SET
                text = NULL,
                sender = NULL,
                channel = 'null',
                timestamp = 0,
                extra_json = NULL
            WHERE id in (:ids);
            """)
    int replaceDataByIds(List<Long> ids);
}
