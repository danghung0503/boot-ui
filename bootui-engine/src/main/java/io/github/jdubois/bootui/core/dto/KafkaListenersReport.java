package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The application's Spring Kafka listener containers (mani fork): pausing one stops polling, so records wait on the
 * topic, and resuming continues from the committed offset.
 */
public record KafkaListenersReport(boolean available, String unavailableReason, List<KafkaListenerDto> listeners) {

    public KafkaListenersReport {
        listeners = DtoCollections.immutableCopy(listeners);
    }
}
