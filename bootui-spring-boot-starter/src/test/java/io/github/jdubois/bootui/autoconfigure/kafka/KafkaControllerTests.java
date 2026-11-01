package io.github.jdubois.bootui.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.web.servlet.MockMvc;

class KafkaControllerTests {

    @SuppressWarnings("unchecked")
    private final ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);

    @Test
    void reportsUnavailableWhenNoKafkaTemplateBeanIsPresent() throws Exception {
        MockMvc mvc = buildMvc(new KafkaActivityRecorder(true, true, 200, 16), null);

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.unavailableReason").value("No KafkaTemplate bean is present"))
                .andExpect(jsonPath("$.maxEntries").value(200))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listsCapturedKafkaActivityWhenKafkaTemplateIsPresent() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 16);
        recorder.recordProduce("orders", 0, "order-42", null, true, null);
        recorder.recordConsume("orders", 0, 41L, "order-42", 12L, true, null, "orders-group", "orderListener");
        MockMvc mvc = buildMvc(recorder, new KafkaTemplate<>(producerFactory));

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(true))
                .andExpect(jsonPath("$.captureKeyEnabled").value(true))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalCaptured").value(2))
                .andExpect(jsonPath("$.messages[0].direction").value("CONSUME"))
                .andExpect(jsonPath("$.messages[0].topic").value("orders"))
                .andExpect(jsonPath("$.messages[0].groupId").value("orders-group"))
                .andExpect(jsonPath("$.messages[0].listenerId").value("orderListener"))
                .andExpect(jsonPath("$.messages[1].direction").value("PRODUCE"));
    }

    @Test
    void reflectsDisabledCaptureAndKeyHashingOnTheReport() throws Exception {
        MockMvc mvc = buildMvc(new KafkaActivityRecorder(false, false, 200, 16), new KafkaTemplate<>(producerFactory));

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.capturing").value(false))
                .andExpect(jsonPath("$.captureKeyEnabled").value(false));
    }

    @Test
    void reportsAvailableWhenMultipleKafkaTemplateBeansArePresent() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<?, ?>> templates = mock(ObjectProvider.class);
        KafkaTemplate<Object, Object> first = new KafkaTemplate<>(producerFactory);
        KafkaTemplate<Object, Object> second = new KafkaTemplate<>(producerFactory);
        when(templates.stream()).thenReturn(Stream.of(first, second));
        KafkaController controller = new KafkaController(
                provider(new KafkaActivityRecorder(true, true, 200, 16)),
                templates,
                new BootUiProperties(),
                provider(null));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/bootui/api/kafka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void clearRemovesAllCapturedMessages() throws Exception {
        KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 200, 16);
        recorder.recordProduce("orders", 0, "order-42", null, true, null);
        MockMvc mvc = buildMvc(recorder, new KafkaTemplate<>(producerFactory));

        mvc.perform(delete("/bootui/api/kafka")).andExpect(status().isNoContent());

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void clearIsANoOpWhenNoRecorderIsPresent() throws Exception {
        MockMvc mvc = buildMvc(null, new KafkaTemplate<>(producerFactory));

        mvc.perform(delete("/bootui/api/kafka")).andExpect(status().isNoContent());
    }

    @Test
    void listsPausesAndResumesListenerContainersAndReportsAMissingRegistry() throws Exception {
        KafkaListenerEndpointRegistry registry = new KafkaListenerEndpointRegistry();
        ContainerProperties containerProperties = new ContainerProperties("orders");
        containerProperties.setGroupId("billing");
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, String> consumerFactory = mock(ConsumerFactory.class);
        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(consumerFactory, containerProperties);
        container.setBeanName("orders-listener");
        container.setConcurrency(3);
        registry.registerListenerContainer(endpoint("orders-listener"), factory(container));
        MockMvc mvc = standaloneSetup(new KafkaController(
                        provider(null),
                        provider(new KafkaTemplate<>(producerFactory)),
                        new BootUiProperties(),
                        provider(registry)))
                .build();

        mvc.perform(get("/bootui/api/kafka/listeners"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.listeners[0].id").value("orders-listener"))
                .andExpect(jsonPath("$.listeners[0].groupId").value("billing"))
                .andExpect(jsonPath("$.listeners[0].topics[0]").value("orders"))
                .andExpect(jsonPath("$.listeners[0].concurrency").value(3))
                .andExpect(jsonPath("$.listeners[0].pauseRequested").value(false));
        mvc.perform(post("/bootui/api/kafka/listeners/orders-listener/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pauseRequested").value(true));
        mvc.perform(post("/bootui/api/kafka/listeners/orders-listener/resume"))
                .andExpect(jsonPath("$.pauseRequested").value(false));
        mvc.perform(post("/bootui/api/kafka/listeners/missing/pause")).andExpect(status().isNotFound());

        mvc = buildMvc(null, new KafkaTemplate<>(producerFactory));
        mvc.perform(get("/bootui/api/kafka/listeners"))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.listeners").isEmpty());
    }

    private static org.springframework.kafka.config.KafkaListenerEndpoint endpoint(String id) {
        org.springframework.kafka.config.KafkaListenerEndpoint endpoint =
                mock(org.springframework.kafka.config.KafkaListenerEndpoint.class);
        when(endpoint.getId()).thenReturn(id);
        return endpoint;
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.kafka.config.KafkaListenerContainerFactory<MessageListenerContainer> factory(
            MessageListenerContainer container) {
        org.springframework.kafka.config.KafkaListenerContainerFactory<MessageListenerContainer> factory =
                mock(org.springframework.kafka.config.KafkaListenerContainerFactory.class);
        when(factory.createListenerContainer(org.mockito.ArgumentMatchers.any()))
                .thenReturn(container);
        return factory;
    }

    private MockMvc buildMvc(KafkaActivityRecorder recorder, KafkaTemplate<?, ?> kafkaTemplate) {
        KafkaController controller = new KafkaController(
                provider(recorder), provider(kafkaTemplate), new BootUiProperties(), provider(null));
        return standaloneSetup(controller).build();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        when(provider.stream()).thenReturn(value == null ? Stream.empty() : Stream.of(value));
        return provider;
    }
}
