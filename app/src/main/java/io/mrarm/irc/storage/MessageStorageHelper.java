package io.mrarm.irc.storage;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import io.mrarm.irc.protocol.dto.ChannelModeMessageInfo;
import io.mrarm.irc.protocol.dto.KickMessageInfo;
import io.mrarm.irc.protocol.dto.MessageInfo;
import io.mrarm.irc.protocol.dto.MessageSenderInfo;
import io.mrarm.irc.protocol.dto.NickChangeMessageInfo;
import io.mrarm.irc.protocol.dto.NickPrefixList;
import io.mrarm.irc.protocol.dto.TopicWhoTimeMessageInfo;

public class MessageStorageHelper {

    private static final String PROP_BATCH = "batch";
    private static final String PROP_NICKCHANGE_NEWNICK = "newNick";
    private static final String PROP_KICK_TARGET = "kickedNick";
    private static final String PROP_CHANNELMODE_ENTRIES = "entries";
    private static final String PROP_TOPICWHOTIME_SET_BY = "setBy";
    private static final String PROP_TOPICWHOTIME_SET_ON = "setOn";

    static final int TYPE_DELETED = -1;


    private static final Gson gson = new Gson();

    static MessageInfo deserializeMessage(MessageSenderInfo sender, Date date, String text,
                                          int typeInt, String extraData) {
        MessageInfo.MessageType type = MessageInfo.MessageType.NORMAL;
        for (MessageInfo.MessageType t : MessageInfo.MessageType.values()) {
            if (t.asInt() == typeInt)
                type = t;
        }
        JsonObject o = gson.fromJson(extraData, JsonObject.class);
        // TODO: These should be moved to builders as well?
        if (type == MessageInfo.MessageType.NICK_CHANGE)
            return new NickChangeMessageInfo(sender, date, o.get(PROP_NICKCHANGE_NEWNICK).getAsString());
        if (type == MessageInfo.MessageType.KICK)
            return new KickMessageInfo(sender, date, o.get(PROP_KICK_TARGET).getAsString(), text);

        MessageInfo.Builder builder;
        if (type == MessageInfo.MessageType.MODE) {
            JsonArray entriesArray = o.get(PROP_CHANNELMODE_ENTRIES).getAsJsonArray();
            List<ChannelModeMessageInfo.Entry> entries = new ArrayList<>(entriesArray.size());
            for (JsonElement e : entriesArray)
                entries.add(gson.fromJson(e.getAsJsonObject(), ChannelModeMessageInfo.Entry.class));
            builder = new ChannelModeMessageInfo.Builder(sender, entries);
        } else if (type == MessageInfo.MessageType.TOPIC_WHOTIME) {
            builder = new TopicWhoTimeMessageInfo.Builder(sender,
                    deserializeSenderInfo(o.get(PROP_TOPICWHOTIME_SET_BY).getAsString(), null),
                    new Date(o.get(PROP_TOPICWHOTIME_SET_ON).getAsLong() * 1000L));
        } else {
            builder = new MessageInfo.Builder(sender, text, type);
        }
        builder.setDate(date);
//        if (o.has(PROP_BATCH)) {
//            // TODO: find the batch
//        }
        return builder.build();
    }

    static String serializeSenderInfo(MessageSenderInfo sender) {
        return (sender.getNickPrefixes() == null ? "" :
                sender.getNickPrefixes().toString()) + " " + sender.getNick() +
                (sender.getUser() != null ? "!" + sender.getUser() : "") +
                (sender.getHost() != null ? "@" + sender.getHost() : "");
    }

    static MessageSenderInfo deserializeSenderInfo(String serialized, UUID uuid) {
        if (serialized == null || serialized.equals(""))
            return null;
        int piof = serialized.indexOf(' ');
        String prefixes = serialized.substring(0, piof);

        String nick, user = null, host = null;
        int iof = serialized.indexOf('!', piof);
        int iof2 = serialized.indexOf('@', (iof == -1 ? piof + 1 : iof + 1));
        if (iof != -1 || iof2 != -1)
            nick = serialized.substring(piof + 1, (iof != -1 ? iof : iof2));
        else
            nick = serialized.substring(piof + 1);
        if (iof != -1)
            user = serialized.substring(iof + 1, (iof2 == -1 ? serialized.length() : iof2));
        if (iof2 != -1)
            host = serialized.substring(iof2 + 1);
        return new MessageSenderInfo(nick, user, host, prefixes.length() > 0 ? new NickPrefixList(prefixes) : null, uuid);
    }

    public static String serializeExtraData(MessageInfo info) {
        JsonObject object = new JsonObject();
        if (info.getBatch() != null)
            object.addProperty(PROP_BATCH, info.getBatch().getUUID().toString());
        if (info instanceof NickChangeMessageInfo) {
            NickChangeMessageInfo nickChangeMessage = ((NickChangeMessageInfo) info);
            object.addProperty(PROP_NICKCHANGE_NEWNICK, nickChangeMessage.getNewNick());
        }
        if (info instanceof ChannelModeMessageInfo) {
            ChannelModeMessageInfo modeMessage = ((ChannelModeMessageInfo) info);
            object.add(PROP_CHANNELMODE_ENTRIES, gson.toJsonTree(modeMessage.getEntries()));
        }
        if (info instanceof KickMessageInfo) {
            KickMessageInfo kickMessage = ((KickMessageInfo) info);
            object.addProperty(PROP_KICK_TARGET, kickMessage.getKickedNick());
        }
        if (info instanceof TopicWhoTimeMessageInfo) {
            TopicWhoTimeMessageInfo topicMessage = ((TopicWhoTimeMessageInfo) info);
            object.addProperty(PROP_TOPICWHOTIME_SET_BY,
                    serializeSenderInfo(topicMessage.getSetBy()));
            object.addProperty(PROP_TOPICWHOTIME_SET_ON,
                    topicMessage.getSetOnDate().getTime() / 1000L);
        }
        return gson.toJson(object);
    }


    static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer b = ByteBuffer.wrap(new byte[16]);
        b.putLong(uuid.getMostSignificantBits());
        b.putLong(uuid.getLeastSignificantBits());
        return b.array();
    }

    static UUID bytesToUUID(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }

}
