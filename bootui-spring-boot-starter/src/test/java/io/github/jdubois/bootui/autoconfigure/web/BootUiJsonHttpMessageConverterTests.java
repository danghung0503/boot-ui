package io.github.jdubois.bootui.autoconfigure.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.HotpatchRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** mani fork: BootUI API request bodies are read with BootUI's own mapper, never the application's. */
class BootUiJsonHttpMessageConverterTests {

    private final BootUiJsonHttpMessageConverter converter = new BootUiJsonHttpMessageConverter(new BootUiProperties());

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void request(String uri) {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("POST", uri)));
    }

    @Test
    void readsBootUiApiBodiesInCamelCaseWhateverTheApplicationMapper() throws Exception {
        request("/bootui/api/hotpatch/compile");
        ResolvableType type = ResolvableType.forClass(HotpatchRequest.class);

        assertThat(converter.canRead(type, MediaType.APPLICATION_JSON)).isTrue();
        Object read = converter.read(
                type,
                new MockHttpInputMessage("{\"className\":\"demo.Fee\",\"source\":\"class Fee {}\",\"reason\":\"r\"}"
                        .getBytes(StandardCharsets.UTF_8)),
                null);
        assertThat(read).isEqualTo(new HotpatchRequest("demo.Fee", "class Fee {}", "r"));
    }

    @Test
    void leavesApplicationRequestsToTheApplicationConverters() {
        request("/api/refunds");
        assertThat(converter.canRead(ResolvableType.forClass(HotpatchRequest.class), MediaType.APPLICATION_JSON))
                .isFalse();

        RequestContextHolder.resetRequestAttributes();
        assertThat(converter.canRead(ResolvableType.forClass(HotpatchRequest.class), MediaType.APPLICATION_JSON))
                .isFalse();
    }
}
