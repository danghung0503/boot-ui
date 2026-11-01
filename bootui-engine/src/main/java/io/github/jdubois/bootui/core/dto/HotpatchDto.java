package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One patch the Hotpatch panel (mani fork) applied to this JVM: lost when the application restarts.
 *
 * @param classes every class it redefined, the class itself and its nested classes
 * @param appliedAt when, in epoch milliseconds
 * @param sha256 the SHA-256 of the bytecode it applied
 */
public record HotpatchDto(String className, List<String> classes, long appliedAt, String reason, String sha256) {

    public HotpatchDto {
        classes = DtoCollections.immutableCopy(classes);
    }
}
