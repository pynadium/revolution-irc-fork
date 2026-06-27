package io.mrarm.irc.protocol.irc;

import java.util.Date;
import java.util.concurrent.Future;

import io.mrarm.irc.protocol.dto.MessageSenderInfo;

public interface ChannelDataStorage {

    class StoredData {

        private String topic;
        private MessageSenderInfo topicSetBy;
        private Date topicSetOn;

        public StoredData(String topic, MessageSenderInfo topicSetBy, Date topicSetOn) {
            this.topic = topic;
            this.topicSetBy = topicSetBy;
            this.topicSetOn = topicSetOn;
        }

        public String getTopic() {
            return topic;
        }

        public MessageSenderInfo getTopicSetBy() {
            return topicSetBy;
        }

        public Date getTopicSetOn() {
            return topicSetOn;
        }

    }

    Future<StoredData> getOrCreateChannelData(String channel);

    Future<Void> updateTopic(String channel, String topic, MessageSenderInfo setBy, Date setOn);

}
