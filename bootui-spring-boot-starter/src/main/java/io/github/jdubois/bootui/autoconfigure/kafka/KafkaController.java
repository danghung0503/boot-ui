package io.github.jdubois.bootui.autoconfigure.kafka;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.KafkaListenerDto;
import io.github.jdubois.bootui.core.dto.KafkaListenersReport;
import io.github.jdubois.bootui.core.dto.KafkaReport;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaMessageDtos;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read/clear API for the dedicated Kafka panel.
 *
 * <p>Available whenever a {@link KafkaTemplate} bean is present ({@code
 * KafkaProducerCaptureBeanPostProcessor}/{@code KafkaConsumerCaptureBeanPostProcessor} wrap it and feed
 * captures into the shared {@link KafkaActivityRecorder}); when no {@link KafkaTemplate} bean can be
 * resolved the panel reports itself unavailable, even though {@link KafkaActivityRecorder} is itself
 * framework-neutral and always registered — same shape as {@code EmailController}.</p>
 *
 * <p>This is a read-mostly view over the exact same recorder that already feeds Live Activity's {@code
 * MESSAGING} entries (there is only ever one buffer), so {@link #clear()} also clears Kafka's Live
 * Activity history, and disabling this panel disables the underlying capture entirely (see {@code
 * BootUiEngineConfiguration.KafkaBackendConfiguration}).</p>
 */
@RestController
@ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/kafka")
public class KafkaController {

    private final ObjectProvider<KafkaActivityRecorder> recorderProvider;
    private final ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider;
    private final BootUiProperties properties;
    private final ObjectProvider<KafkaListenerEndpointRegistry> registryProvider;

    public KafkaController(
            ObjectProvider<KafkaActivityRecorder> recorderProvider,
            ObjectProvider<KafkaTemplate<?, ?>> kafkaTemplateProvider,
            BootUiProperties properties,
            ObjectProvider<KafkaListenerEndpointRegistry> registryProvider) {
        this.recorderProvider = recorderProvider;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.properties = properties;
        this.registryProvider = registryProvider;
    }

    @GetMapping
    public KafkaReport list() {
        KafkaActivityRecorder recorder = availableRecorder();
        if (recorder == null) {
            return KafkaReport.unavailable(
                    "No KafkaTemplate bean is present", properties.getKafka().getMaxEntries());
        }
        return KafkaMessageDtos.toReport(recorder);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        KafkaActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            recorder.clear();
        }
    }

    /**
     * The application's listener containers (mani fork). {@code POST …/listeners/{id}/pause|resume} pauses or resumes
     * one; both are actions of this panel, refused by its read-only policy.
     */
    @GetMapping("/listeners")
    public KafkaListenersReport listeners() {
        KafkaListenerContainers containers = listenerContainers();
        return containers == null
                ? new KafkaListenersReport(
                        false, "No Spring Kafka listener registry (@EnableKafka) is present", List.of())
                : new KafkaListenersReport(true, null, containers.list());
    }

    @PostMapping("/listeners/{id}/{action:pause|resume}")
    public KafkaListenerDto changeListener(@PathVariable String id, @PathVariable String action) {
        KafkaListenerContainers containers = listenerContainers();
        KafkaListenerDto changed = containers == null ? null : containers.change(id, "pause".equals(action));
        if (changed == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No Kafka listener " + id);
        }
        return changed;
    }

    private KafkaListenerContainers listenerContainers() {
        KafkaListenerEndpointRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? null : new KafkaListenerContainers(registry);
    }

    private KafkaActivityRecorder availableRecorder() {
        if (kafkaTemplateProvider.stream().findAny().isEmpty()) {
            return null;
        }
        return recorderProvider.getIfAvailable();
    }
}
