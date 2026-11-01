package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.HotpatchCompileDto;
import io.github.jdubois.bootui.core.dto.HotpatchDto;
import io.github.jdubois.bootui.core.dto.HotpatchReport;
import io.github.jdubois.bootui.core.dto.HotpatchRequest;
import io.github.jdubois.bootui.core.dto.HotpatchSourceDto;
import io.github.jdubois.bootui.engine.hotpatch.HotpatchService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Hotpatch panel (mani fork): edit an application class's local source, compile it, and redefine the class in this
 * JVM through the BootUI agent, method bodies only. A thin binding of the framework-neutral {@link HotpatchService};
 * its refusals are 400 problems with a readable detail. The panel is action-capable, so {@code bootui.read-only} and
 * {@code bootui.panels.hotpatch.read-only|enabled} gate its writes like every other action panel.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/hotpatch")
public class HotpatchController {

    private final HotpatchService service;

    public HotpatchController(HotpatchService service) {
        this.service = service;
    }

    @GetMapping
    public HotpatchReport report() {
        return service.report();
    }

    @GetMapping("/classes")
    public List<String> classes(@RequestParam(defaultValue = "") String q) {
        return service.classes(q);
    }

    @GetMapping("/source")
    public HotpatchSourceDto source(@RequestParam("class") String className) {
        return refused(() -> service.source(className));
    }

    @PostMapping("/compile")
    public HotpatchCompileDto compile(@RequestBody HotpatchRequest request) {
        return refused(() -> service.compile(request));
    }

    @PostMapping("/patches")
    public HotpatchDto apply(@RequestBody HotpatchRequest request) {
        return refused(() -> service.apply(request));
    }

    @DeleteMapping("/patches/{className}")
    public HotpatchDto revert(@PathVariable String className) {
        return refused(() -> service.revert(className));
    }

    private static <T> T refused(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
