package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Hotpatch panel's state (mani fork).
 *
 * @param available whether patches can be applied: the BootUI agent is attached with the panel's bridge and this
 *     JVM has a Java compiler
 * @param unavailableReason why not, or {@code null}
 * @param annotationProcessor the Lombok jar compilations use as annotation processor, or {@code null} when none was
 *     found
 * @param packages the application packages a patch may change
 * @param patches the patches in effect, oldest first
 */
public record HotpatchReport(
        boolean available,
        String unavailableReason,
        String annotationProcessor,
        List<String> packages,
        List<HotpatchDto> patches) {

    public HotpatchReport {
        packages = DtoCollections.immutableCopy(packages);
        patches = DtoCollections.immutableCopy(patches);
    }
}
