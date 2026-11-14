package io.mrarm.irc.storage.db;


import static io.mrarm.irc.storage.MessageStorageHelper.serializeExtraData;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import io.mrarm.irc.protocol.dto.MessageInfo;

@Entity(
        tableName = "messages_logs",
        indices = {
                @Index("serverId"),
                @Index({"serverId", "channel", "id"})
        }
)
public class MessageEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public long id;

    @NonNull
    @ColumnInfo(name = "serverId")
    public UUID serverId;

    @NonNull
    @ColumnInfo(name = "channel")
    public String channel;

    @NonNull
    @ColumnInfo(name = "kind")
    public MessageKind kind;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @NonNull
    @ColumnInfo(name = "type")
    public int type;

    @ColumnInfo(name = "text")
    public String text;

    @ColumnInfo(name = "sender")
    public String sender;

    @ColumnInfo(name = "extra_json")
    public String extraJson;

    @ColumnInfo(name = "dedupe_key")
    @Nullable
    public String dedupeKey;

    @ColumnInfo(name = "aprox_row_size")
    @NonNull
    public Long aproxRowSize;

    public static MessageEntity from(UUID serverId, String channel, MessageInfo info) {
        boolean isChannel = !channel.isEmpty() && "#&+!".indexOf(channel.charAt(0)) >= 0;

        MessageEntity e = new MessageEntity();
        e.serverId = serverId;
        e.channel = channel;
        e.timestamp = info.getDate() != null ? info.getDate().getTime() : System.currentTimeMillis();
        e.text = info.getMessage();
        e.type = (info.getType() != null ? info.getType().asInt() : MessageInfo.MessageType.NORMAL.asInt());
        e.sender = (info.getSender() != null ? info.getSender().getNick() : null);
        e.kind = isChannel ? MessageKind.CHANNEL : MessageKind.PRIVATE;
        e.extraJson = serializeExtraData(info);
        e.dedupeKey = info.isPlayback() ? computeDedupeKey(serverId, channel, info) : null;
        e.aproxRowSize = computeSize(e);
        return e;
    }

    private static String computeDedupeKey(UUID serverId, String channel, MessageInfo msg) {

        String sender = msg.getSender() != null
                ? msg.getSender().getNick().trim()
                : "";

        String text = msg.getMessage() != null
                ? msg.getMessage().trim()
                : "";

        long bucket = msg.getDate().getTime() / 60_000;

        return sha1(
                serverId + "|" +
                channel + "|" +
                sender + "|" +
                text + "|" +
                bucket
        );
    }

    public static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static long computeSize(MessageEntity e) {
        long size = 0;

        // Strings are UTF-16 in memory, but stored as UTF-8 in SQLite.
        size += utf8Length(e.text);
        size += utf8Length(e.channel);
        size += utf8Length(e.dedupeKey);

        if (e.sender != null) {
            size += utf8Length(e.sender);
        }

        if (e.extraJson != null) {
            size += utf8Length(e.extraJson);
        }

        size += 20; // row header for 10 columns table (conservative in case of more cols)
        size += 37; // fixed UUID.toString() value
        size += 8; // fixed enum value CHANNEL vs PRIVATE
        size += 8; // fixed timestamp length
        size += 1; // type INTEGER varint
        size += 1; // aprox_row_size column inteslt (INTEGER varint)
        size += 10; // aprox B-tree cell overhead
        size += 45; // index on serverId
        size += 55; // index on (serverId, timestamp)
        size += 55; // index on (serverId, channel)
        // total aprox 240

        return size;
    }

    private static long utf8Length(CharSequence s) {
        if (s == null) return 0;
        long bytes = 0;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c <= 0x7F) {
                bytes += 1;
            } else if (c <= 0x7FF) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                // surrogate pair → 4-byte UTF-8 codepoint
                bytes += 4;
                i++; // skip the next char which is the low surrogate
            } else {
                // U+0800 to U+FFFF
                bytes += 3;
            }
        }

        return bytes;
    }
}
