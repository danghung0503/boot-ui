package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.core.dto.KafkaListenerDto;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The Kafka panel's listener controls (mani fork): the registry's containers, and pause or resume of one. Pausing stops
 * polling while the consumer keeps its group membership and partitions, so records wait on the topic; resuming
 * continues from the committed offset. Acts on this JVM only.
 */
final class KafkaListenerContainers {

    private final KafkaListenerEndpointRegistry registry;

    KafkaListenerContainers(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    List<KafkaListenerDto> list() {
        return registry.getAllListenerContainers().stream()
                .sorted(Comparator.comparing(c -> String.valueOf(c.getListenerId())))
                .map(KafkaListenerContainers::dto)
                .toList();
    }

    /** Pauses or resumes the container {@code id}; {@code null} when there is none. */
    KafkaListenerDto change(String id, boolean pause) {
        MessageListenerContainer container = registry.getListenerContainer(id);
        if (container == null) {
            container = registry.getAllListenerContainers().stream()
                    .filter(c -> id.equals(c.getListenerId()))
                    .findFirst()
                    .orElse(null);
        }
        if (container == null) {
            return null;
        }
        if (pause) {
            container.pause();
        } else {
            container.resume();
        }
        return dto(container);
    }

    private static KafkaListenerDto dto(MessageListenerContainer c) {
        String[] topics = c.getContainerProperties().getTopics();
        List<String> topicList = topics != null
                ? Arrays.asList(topics)
                : c.getContainerProperties().getTopicPattern() != null
                        ? List.of(c.getContainerProperties().getTopicPattern().pattern())
                        : List.of();
        int concurrency = c instanceof ConcurrentMessageListenerContainer<?, ?> cc ? cc.getConcurrency() : 1;
        var assigned = c.getAssignedPartitions();
        List<String> partitions = assigned == null
                ? List.of()
                : assigned.stream().map(TopicPartition::toString).sorted().toList();
        return new KafkaListenerDto(
                c.getListenerId(),
                c.getGroupId(),
                topicList,
                concurrency,
                c.isRunning(),
                c.isPauseRequested(),
                c.isContainerPaused(),
                partitions);
    }
}
