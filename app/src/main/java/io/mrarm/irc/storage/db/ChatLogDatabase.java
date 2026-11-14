package io.mrarm.irc.storage.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                MessageEntity.class,
                ConversationStateEntity.class
        },
        version = 1
)
@TypeConverters({Converters.class})
public abstract class ChatLogDatabase extends RoomDatabase {

    public abstract MessageDao messageDao();

    public abstract ConversationStateDao conversationStateDao();

    public static final String FILE_NAME = "chatlogs.db";

    private static volatile ChatLogDatabase INSTANCE;

    // True while a backup restore replaces chatlogs.db; guarded by ChatLogDatabase.class
    private static boolean sRestoreInProgress;

    /**
     * @throws IllegalStateException while a restore is in progress (see {@link #beginRestore()}).
     *         Callers run on background threads that already catch and log failures.
     */
    public static ChatLogDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ChatLogDatabase.class) {
                if (sRestoreInProgress)
                    throw new IllegalStateException("chatlogs.db is being restored");
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    ChatLogDatabase.class,
                                    FILE_NAME
                            )
                            // No destructive fallback: a version bump without a Migration must fail
                            // loudly instead of silently wiping the user's chat logs.
                            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                            .addCallback(new RoomDatabase.Callback() {
                                @Override
                                public void onOpen(@NonNull SupportSQLiteDatabase db) {
                                    db.execSQL("""
                                            CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedupe 
                                            ON messages_logs(dedupe_key)
                                            WHERE dedupe_key IS NOT NULL
                                            """
                                    );
                                }
                            })
                            .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Closes the database before a restore replaces chatlogs.db, and refuses to reopen it
     * until {@link #endRestore()}: any background write in the meantime would otherwise open
     * the file while it is being replaced. Always pair with endRestore() in a finally block.
     */
    public static void beginRestore() {
        synchronized (ChatLogDatabase.class) {
            sRestoreInProgress = true;
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
            }
        }
    }

    public static void endRestore() {
        synchronized (ChatLogDatabase.class) {
            sRestoreInProgress = false;
        }
    }
}
