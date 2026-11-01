package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Spring Kafka listener container (mani fork).
 *
 * @param pauseRequested a pause was asked for: it takes effect at the consumer's next poll
 * @param paused every consumer of the container has paused
 * @param assignedPartitions the partitions this instance holds, as {@code topic-partition}
 */
public record KafkaListenerDto(
        String id,
        String groupId,
        List<String> topics,
        int concurrency,
        boolean running,
        boolean pauseRequested,
        boolean paused,
        List<String> assignedPartitions) {

    public KafkaListenerDto {
        topics = DtoCollections.immutableCopy(topics);
        assignedPartitions = DtoCollections.immutableCopy(assignedPartitions);
    }
}
