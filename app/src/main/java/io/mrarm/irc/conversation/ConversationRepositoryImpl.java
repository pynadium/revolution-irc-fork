package io.mrarm.irc.conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.mrarm.irc.chatlib.dto.MessageList;
import io.mrarm.irc.chatlib.message.MessageListener;
import io.mrarm.irc.message.MessageBus;
import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.storage.MessageStorageRepository;

public class ConversationRepositoryImpl implements ConversationRepository {

    private final MessageBus bus;
    private final Map<ConversationListener, MessageListener> mSubscription = new HashMap<>();
    private final MessageStorageRepository repository;

    public ConversationRepositoryImpl(MessageStorageRepository repo) {
        this.repository = repo;
        this.bus = null;
    }

    public ConversationRepositoryImpl(MessageStorageRepository repo, MessageBus bus) {
        this.repository = repo;
        this.bus = bus;
    }

    @Override
    public void subscribe(String channel, ConversationListener listener) {
        if (bus == null)
            return;
        MessageListener wrapper = (ch, info, id) -> listener.onMessage(ch,
                ConversationMessageMapper.map(info, id), id);

        mSubscription.put(listener, wrapper);
        bus.subscribe(channel, wrapper);
    }

    @Override
    public void unsubscribe(String channel, ConversationListener listener) {
        if (bus == null)
            return;
        MessageListener wrapper = mSubscription.remove(listener);
        if (wrapper != null) {
            bus.unsubscribe(channel, wrapper);
        }
    }

    @Override
    public void loadRecentAsync(UUID serverId, String channel, int limit,
                                List<Integer> excludedTypes,
                                Consumer<List<ConversationMessage>> callback) {

        repository.loadRecentAsync(serverId, channel, limit, excludedTypes, msgList -> {
            callback.accept(toMapped(msgList));
        });
    }

    @Override
    public void loadOlderAsync(UUID serverId, String channel, long beforeId, int limit,
                               List<Integer> excludeTypes,
                               Consumer<List<ConversationMessage>> callback) {

        repository.loadOlderAsync(serverId, channel, beforeId, limit, excludeTypes,
                entities -> callback.accept(toMapped(repository.toMessageListFromRoom(entities))));
    }


    @Override
    public void loadNewerAsync(UUID serverId, String channel, long afterId, int limit,
                               List<Integer> excludeTypes,
                               Consumer<List<ConversationMessage>> callback) {
        repository.loadNewerAsync(serverId, channel, afterId, limit, excludeTypes,
                entities -> callback.accept(toMapped(repository.toMessageListFromRoom(entities))));
    }

    @Override
    public void loadNearAsync(UUID serverId, String channel, long centerId, int limit,
                              List<Integer> excludeTypes,
                              Consumer<List<ConversationMessage>> callback) {
        repository.loadNearAsync(serverId, channel, centerId, limit, excludeTypes,
                entities -> callback.accept(toMapped(repository.toMessageListFromRoom(entities))));
    }

    @Override
    public void loadFilteredBeforeAsync(UUID serverId, String channel, String sender,
                                        long beforeId, int limit, List<Integer> excludedTypes,
                                        Consumer<List<ConversationMessage>> callback) {

        repository.loadFilteredBeforeAsync(serverId, channel, sender, beforeId, limit,
                excludedTypes, messageEntities -> {
            MessageList list = repository.toMessageListFromRoom(messageEntities);
            List<ConversationMessage> mapped = new ArrayList<>(list.getMessages().size());
            for (int i = 0; i < list.getMessages().size(); i++) {
                mapped.add(ConversationMessageMapper.map(list.getMessages().get(i),
                        list.getMessageIds().get(i)));
            }
            callback.accept(mapped);
        });
    }

    @Override
    public void getDistinctServerIdsAsync(Consumer<List<UUID>> callback) {
        repository.getDistinctServerIdsAsync(callback);
    }

    @Override
    public void getDistinctChannelAsync(UUID serverId, Consumer<List<String>> callback) {
        repository.getDistinctChannelsAsync(serverId, callback);
    }


    @Override
    public void getDistinctSendersAsync(UUID serverId, String channel,
                                        Consumer<List<String>> callback) {
        repository.getDistinctSendersAsync(serverId, channel, callback);
    }

    private List<ConversationMessage> toMapped(MessageList list) {
        List<ConversationMessage> mapped = new ArrayList<>(list.getMessages().size());
        for (int i = 0; i < list.getMessages().size(); i++)
            mapped.add(ConversationMessageMapper.map(list.getMessages().get(i),
                    list.getMessageIds().get(i)));
        return mapped;
    }
}
