package io.mrarm.irc.storage;

import static io.mrarm.irc.storage.MessageStorageHelper.deserializeMessage;

import android.content.Context;
import android.util.Log;
import android.util.Pair;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.ServerConfigData;
import io.mrarm.irc.config.ServerConfigManager;
import io.mrarm.irc.infrastructure.threading.AppAsyncExecutor;
import io.mrarm.irc.protocol.dto.MessageId;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageList;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.RoomMessageId;
import io.mrarm.irc.storage.db.ChatLogDatabase;
import io.mrarm.irc.storage.db.ConversationStateDao;
import io.mrarm.irc.storage.db.IdSizePair;
import io.mrarm.irc.storage.db.MessageDao;
import io.mrarm.irc.storage.db.MessageEntity;
import io.mrarm.irc.storage.db.MessageKind;

public class MessageStorageRepository {
    private static volatile MessageStorageRepository INSTANCE;

    private final ChatLogDatabase db;
    private final MessageDao dao;
    private final ConversationStateDao conversationStateDao;
    private final Context context;
    private static final int AUTO_CLEANUP_CHECK_EVERY = 500;
    private static final double AUTO_CLEANUP_HYSTERESIS = 1.10; // 10%

    private int insertCounter = 0;

    // Global monitor lock for all maintenance & write operations
    private final Object maintenanceLock = new Object();

    private MessageStorageRepository(Context ctx) {
        context = ctx;
        db = ChatLogDatabase.getInstance(ctx);
        dao = db.messageDao();
        conversationStateDao = db.conversationStateDao();
    }

    public static MessageStorageRepository getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (MessageStorageRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MessageStorageRepository(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    public long insertMessage(MessageEntity msg) {
        synchronized (maintenanceLock) {
            long id = dao.insert(msg);

            if (id == -1 && msg.dedupeKey != null) {
                long existing = dao.findIdByDedupeKey(msg.dedupeKey);
                Log.d("[MessageStorageRepository]", "Playback dedupe hit, reusing id=" + existing);
                id = existing;
            }

            if (++insertCounter >= AUTO_CLEANUP_CHECK_EVERY) {
                insertCounter = 0;
                considerAutoCleanup();
            }
            return id;
        }
    }

    // Async variants
    public void loadOlderAsync(UUID serverId, String channel, long beforeId, int limit,
                               List<Integer> excludeTypes,
                               Consumer<List<MessageEntity>> callback) {
        AppAsyncExecutor.io(() -> dao.loadBefore(serverId, channel, beforeId, limit, excludeTypes),
                callback);
    }

    public void loadNewerAsync(UUID serverId, String channel, long afterId, int limit,
                               List<Integer> excludeTypes,
                               Consumer<List<MessageEntity>> callback) {
        AppAsyncExecutor.io(() -> dao.loadAfter(serverId, channel, afterId, limit, excludeTypes),
                callback);
    }

    public void loadRecentAsync(UUID serverId, String channel, int limit,
                                List<Integer> excludeTypes,
                                Consumer<MessageList> uiCallback) {
        AppAsyncExecutor.io(() -> toMessageListFromRoom(dao.loadRecent(serverId, channel, limit, excludeTypes)),
                uiCallback
        );
    }

    // Log viewer: filter discovery + filtered paging
    public void getDistinctServerIdsAsync(Consumer<List<UUID>> callback) {
        AppAsyncExecutor.io(dao::getDistinctServerIds, callback);
    }

    public void getDistinctChannelsAsync(UUID serverId, Consumer<List<String>> callback) {
        AppAsyncExecutor.io(() -> {
            List<Long> ids = dao.getMostRecentIdPerChannelGroup(serverId);
            List<MessageEntity> rows = dao.findByIds(ids);
            List<String> result = new ArrayList<>(rows.size());
            for (MessageEntity e : rows) {
                // Prefer the real-cased contact nick from an incoming row (sender == channel,
                // case-insensitively) over `channel` itself, which is forced lowercase for DMs.
                result.add(e.sender != null && e.sender.equalsIgnoreCase(e.channel) ? e.sender : e.channel);
            }
            result.sort(String::compareToIgnoreCase);
            return result;
        }, callback);
    }

    public void getDistinctSendersAsync(UUID serverId, String channel, Consumer<List<String>> callback) {
        AppAsyncExecutor.io(() -> dao.getDistinctSenders(serverId, channel), callback);
    }

    public void loadFilteredBeforeAsync(UUID serverId, String channel, String sender, long beforeId,
                                         int limit, List<Integer> excludeTypes,
                                         Consumer<List<MessageEntity>> callback) {
        AppAsyncExecutor.io(() -> dao.loadFilteredBefore(serverId, channel, sender, beforeId, limit, excludeTypes),
                callback);
    }

    /**
     * Streams every row matching the filter to {@code out}, oldest first, one line per message.
     * Must be called off the main thread; caller owns opening/closing {@code out}.
     */
    public void exportFilteredToWriter(UUID serverId, String channel, String sender,
                                        List<Integer> excludeTypes, Writer out) throws IOException {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        int batchSize = 500;
        long beforeId = Long.MAX_VALUE;

        List<MessageEntity> batch;
        List<List<MessageEntity>> batches = new ArrayList<>();
        do {
            batch = dao.loadFilteredBefore(serverId, channel, sender, beforeId, batchSize, excludeTypes);
            if (batch.isEmpty())
                break;
            batches.add(batch);
            beforeId = batch.get(batch.size() - 1).id;
        } while (batch.size() == batchSize);

        // batches are newest-first overall, each batch newest-first internally; reverse both
        for (int i = batches.size() - 1; i >= 0; --i) {
            List<MessageEntity> b = batches.get(i);
            for (int j = b.size() - 1; j >= 0; --j) {
                MessageEntity e = b.get(j);
                out.write("[" + fmt.format(new Date(e.timestamp)) + "] "
                        + (e.sender != null ? e.sender + ": " : "")
                        + (e.text != null ? e.text : ""));
                out.write("\n");
            }
        }
    }

    public void loadNearAsync(UUID serverId, String channel, long centerId, int limit,
                              List<Integer> excludeTypes,
                              Consumer<List<MessageEntity>> callback) {

        AppAsyncExecutor.io(() -> {
            // older → chronological descending, so reverse later
            List<MessageEntity> older = dao.loadBefore(serverId, channel, centerId, limit, excludeTypes);

            // newer → chronological ascending
            List<MessageEntity> newer = dao.loadAfter(serverId, channel, centerId, limit, excludeTypes);

            List<MessageEntity> combined = new ArrayList<>(older.size() + newer.size());

            // Put older first (correct chronological order)
            older.sort(Comparator.comparingLong(a -> a.id));
            combined.addAll(older);

            // Add the center message itself
            MessageEntity center = dao.findById(centerId);
            if (center != null)
                combined.add(center);

            // Add newer
            combined.addAll(newer);

            return combined;

        }, callback);
    }

    public MessageList toMessageListFromRoom(List<MessageEntity> entities) {
        List<Pair<MessageInfo, MessageId>> pairs = new ArrayList<>(entities.size());

        for (MessageEntity e : entities) {
            MessageSenderInfo sender = (e.sender != null
                    ? new MessageSenderInfo(e.sender, null, null, null, null)
                    : null);

            MessageInfo info = deserializeMessage(sender, new Date(e.timestamp), e.text, e.type, e.extraJson);
            MessageId id = new RoomMessageId(e.id);
            pairs.add(new Pair<>(info, id));
        }

        pairs.sort(Comparator.comparing(p -> p.first.getDate()));

        List<MessageInfo> infos = new ArrayList<>(pairs.size());
        List<MessageId> ids = new ArrayList<>(pairs.size());
        for (Pair<MessageInfo, MessageId> p : pairs) {
            infos.add(p.first);
            ids.add(p.second);
        }

        return new MessageList(infos, ids, null, null);
    }

    public void deleteMessages(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;

        synchronized (maintenanceLock) {
            db.runInTransaction(() -> dao.replaceDataByIds(ids));

            db.runInTransaction(() -> {
                dao.deleteByIds(ids);
            });
        }

        compactUnlocked();
    }


    public void deleteLogsForServer(UUID serverId) {
        synchronized (maintenanceLock) {
            db.runInTransaction(() -> dao.replaceDataByServer(serverId));

            db.runInTransaction(() -> {
                dao.deleteByServer(serverId);
                conversationStateDao.deleteByServer(serverId);
            });
        }
        compactUnlocked();
    }

    public void deleteAllLogs() {
        synchronized (maintenanceLock) {
            db.runInTransaction(dao::replaceAll);

            db.runInTransaction(() -> {
                dao.deleteAll();
                conversationStateDao.clear();
            });
        }
        compactUnlocked();
    }

    private void compactUnlocked() {
        SupportSQLiteDatabase raw = db.getOpenHelper().getWritableDatabase();

        try {
            raw.execSQL("PRAGMA wal_checkpoint(TRUNCATE);");
            raw.close();
        } catch (Exception ignored) {
        }

        try {
            raw.execSQL("VACUUM;");
        } catch (Exception ignored) {
        }

        wipeFile(new File(context.getDatabasePath("chatlogs.db") + "-wal"), true);
        wipeFile(new File(context.getDatabasePath("chatlogs.db") + "-shm"), false);
    }


    private void wipeFile(File f, boolean resetLength) {
        if (!f.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            long len = f.length();
            byte[] zero = new byte[4096];
            long pos = 0;
            while (pos < len) {
                int toWrite = (int) Math.min(zero.length, len - pos);
                raf.write(zero, 0, toWrite);
                pos += toWrite;
            }

            if (resetLength) {
                raf.setLength(0);
            }

        } catch (IOException ex) {
            Log.d("Catched exception while removing data: ", ex.getMessage());
        }
    }

    private void considerAutoCleanup() {
        long globalLimit = AppSettings.getStorageLimitGlobal();
        if (globalLimit >= 0) {
            Long usageObj = dao.getGlobalUsage();
            long usage = usageObj != null ? usageObj : 0L;

            if (usage > (long) (globalLimit * AUTO_CLEANUP_HYSTERESIS)) {
                enforceGlobalLimit(globalLimit);
            }
        }

        considerAutoCleanupPerServer();
    }

    private void considerAutoCleanupPerServer() {
        long defaultServerLimit = AppSettings.getStorageLimitServer();

        for (ServerConfigData server : ServerConfigManager.getInstance(context).getServers()) {
            // storageLimit == 0 means "use default", -1 means "no limit"
            long limit = server.storageLimit == 0L ? defaultServerLimit : server.storageLimit;
            if (limit < 0) continue;

            Long usageObj = dao.getUsageForServer(server.uuid);
            long usage = usageObj != null ? usageObj : 0L;

            if (usage > (long) (limit * AUTO_CLEANUP_HYSTERESIS)) {
                enforceServerLimit(server.uuid, limit);
            }
        }
    }

    public static class CleanupResult {
        private static final int CLEANUP_BATCH = 500;

        public final int rowsDeleted;
        public final long bytesFreed;

        public CleanupResult(int rowsDeleted, long bytesFreed) {
            this.rowsDeleted = rowsDeleted;
            this.bytesFreed = bytesFreed;
        }
    }

    public CleanupResult enforceGlobalLimit(long globalLimitBytes) {
        if (globalLimitBytes < 0) {
            return new CleanupResult(0, 0); // no limit
        }

        synchronized (maintenanceLock) {
            Long usageObj = dao.getGlobalUsage();
            Log.d("Enforce Global cleaning hit: ", String.valueOf(dao.getGlobalUsage()));
            long usage = usageObj != null ? usageObj : 0L;

            if (usage <= globalLimitBytes) {
                return new CleanupResult(0, 0);
            }

            long toFree = usage - globalLimitBytes;
            long freed = 0L;
            int rowsDeleted = 0;

            while (freed < toFree) {
                List<IdSizePair> batch = dao.selectOldestGlobalByKind(MessageKind.CHANNEL, CleanupResult.CLEANUP_BATCH);
                if (batch.isEmpty()) {
                    // No CHANNEL messages left, fall back to oldest overall
                    batch = dao.selectOldestGlobal(CleanupResult.CLEANUP_BATCH);
                }
                if (batch.isEmpty()) break;

                List<Long> ids = new ArrayList<>(batch.size());
                for (IdSizePair p : batch) {
                    ids.add(p.id);
                    freed += p.aproxRowSize;
                    if (freed >= toFree) break;
                }

                int deleted = dao.deleteByIds(ids);
                rowsDeleted += deleted;

                if (deleted == 0) break; // safety
            }

            return new CleanupResult(rowsDeleted, freed);
        }
    }

    public CleanupResult enforceServerLimit(UUID serverId, long serverLimitBytes) {
        if (serverId == null || serverLimitBytes < 0) {
            return new CleanupResult(0, 0); // no-op
        }


        synchronized (maintenanceLock) {
            Long usageObj = dao.getUsageForServer(serverId);
            long usage = usageObj != null ? usageObj : 0L;
            Log.d("Enforce Server cleaning hit: ", String.valueOf(usage));

            if (usage <= serverLimitBytes) {
                return new CleanupResult(0, 0);
            }

            long toFree = usage - serverLimitBytes;
            long freed = 0L;
            int rowsDeleted = 0;

            while (freed < toFree) {
                List<IdSizePair> batch =
                        dao.selectOldestForServerByKind(serverId, MessageKind.CHANNEL, CleanupResult.CLEANUP_BATCH);

                if (batch.isEmpty()) {
                    // No CHANNEL messages left for this server, fall back to oldest overall
                    batch = dao.selectOldestForServer(serverId, CleanupResult.CLEANUP_BATCH);
                }

                if (batch.isEmpty()) break;

                List<Long> ids = new ArrayList<>(batch.size());
                for (IdSizePair p : batch) {
                    ids.add(p.id);
                    freed += p.aproxRowSize;
                    if (freed >= toFree) break;
                }

                int deleted = dao.deleteByIds(ids);
                rowsDeleted += deleted;

                if (deleted == 0) break; // safety
            }

            return new CleanupResult(rowsDeleted, freed);
        }
    }
}
