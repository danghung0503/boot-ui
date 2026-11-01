package io.github.jdubois.bootui.engine.hotpatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.HotpatchRequest;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import org.junit.jupiter.api.Test;

class HotpatchServiceTests {

    private final HotpatchService service = new HotpatchService(
            AgentBridgeAccess.absent(), () -> null, () -> null, getClass().getClassLoader());

    @Test
    void isUnavailableWithoutAnAgentCarryingTheHotpatchBridge() {
        assertThat(service.unavailableReason()).startsWith(HotpatchService.REQUIREMENT);
        assertThat(service.report().available()).isFalse();
        assertThat(service.report().patches()).isEmpty();
    }

    @Test
    void refusesToApplyWithoutAReasonOrOutsideTheApplicationPackages() {
        assertThatThrownBy(() -> service.apply(new HotpatchRequest("demo.Fee", "class Fee {}", " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> service.apply(new HotpatchRequest("demo.Fee", "class Fee {}", "fix")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(HotpatchService.REQUIREMENT);
        assertThatThrownBy(() -> service.source("java.lang.String"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not in the application's packages");
        assertThatThrownBy(() -> service.revert("bad name!")).hasMessageContaining("Invalid class name");
    }
}
