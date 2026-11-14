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

    private static volatile ChatLogDatabase INSTANCE;

    public static ChatLogDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ChatLogDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    ChatLogDatabase.class,
                                    "chatlogs.db"
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

    public static void closeInstance() {
        synchronized (ChatLogDatabase.class) {
            if (INSTANCE != null) {
                INSTANCE.close();
                INSTANCE = null;
            }
        }
    }
}
