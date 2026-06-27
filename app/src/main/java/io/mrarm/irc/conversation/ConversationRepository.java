package io.mrarm.irc.conversation;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import io.mrarm.irc.model.ConversationMessage;

public interface ConversationRepository {

    void subscribe(String channel, ConversationListener listener);

    void unsubscribe(String channel, ConversationListener listener);

    void loadRecentAsync(UUID serverId, String channel, int limit, List<Integer> excludedTypes,
                         Consumer<List<ConversationMessage>> callback);

    void loadOlderAsync(UUID serverId, String channel, long beforeId, int limit,
                        List<Integer> excludeTypes, Consumer<List<ConversationMessage>> callback);

    void loadNewerAsync(UUID serverId, String channel, long afterId, int limit,
                        List<Integer> excludeTypes, Consumer<List<ConversationMessage>> callback);

    void loadNearAsync(UUID serverId, String channel, long centerId, int limit,
                       List<Integer> excludeTypes, Consumer<List<ConversationMessage>> callback);

    void loadFilteredBeforeAsync(UUID serverId, String channel, String sender, long beforeId,
                                 int limit, List<Integer> excludedTypes,
                                 Consumer<List<ConversationMessage>> callback);

    void getDistinctServerIdsAsync(Consumer<List<UUID>> callback);

    void getDistinctChannelAsync(UUID serverId, Consumer<List<String>> callback);

    void getDistinctSendersAsync(UUID serverId, String channel, Consumer<List<String>> callback);
}
