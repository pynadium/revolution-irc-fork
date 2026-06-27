package io.mrarm.irc.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import io.mrarm.irc.chatlib.dto.MessageList;
import io.mrarm.irc.model.ConversationMessage;
import io.mrarm.irc.storage.MessageStorageRepository;

public class ConversationRepositoryImpl implements ConversationRepository {

    private final MessageStorageRepository repository;

    public ConversationRepositoryImpl(MessageStorageRepository repo) {
        this.repository = repo;
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
    public void getDistinctSendersAsync(UUID serverId, String channel, Consumer<List<String>> callback) {
        repository.getDistinctSendersAsync(serverId, channel, callback);
    }
}
