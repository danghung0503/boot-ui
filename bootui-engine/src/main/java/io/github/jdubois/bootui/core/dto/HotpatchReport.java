package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The Hotpatch panel's state (mani fork).
 *
 * @param available whether patches can be applied: the BootUI agent is attached with the panel's bridge and this
 *     JVM has a Java compiler
 * @param unavailableReason why not, or {@code null}
 * @param compiler the compiler patches are built with: {@code javac <version>} on a JDK, {@code javac <version>
 *     (bundled)} on a JRE whose application bundles {@code jdk.compiler}, or {@code null} when there is none
 * @param annotationProcessor the Lombok jar compilations use as annotation processor, or {@code null} when none was
 *     found
 * @param packages the application packages a patch may change
 * @param patches the patches in effect, oldest first
 */
public record HotpatchReport(
        boolean available,
        String unavailableReason,
        String compiler,
        String annotationProcessor,
        List<String> packages,
        List<HotpatchDto> patches) {

    public HotpatchReport {
        packages = DtoCollections.immutableCopy(packages);
        patches = DtoCollections.immutableCopy(patches);
    }
}
