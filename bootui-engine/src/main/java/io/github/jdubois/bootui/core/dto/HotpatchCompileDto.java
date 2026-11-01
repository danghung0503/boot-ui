package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A compilation of patched source.
 *
 * @param diagnostics the compiler's errors and warnings, as {@code line N: message}
 */
public record HotpatchCompileDto(boolean ok, List<String> diagnostics) {

    public HotpatchCompileDto {
        diagnostics = DtoCollections.immutableCopy(diagnostics);
    }
}
