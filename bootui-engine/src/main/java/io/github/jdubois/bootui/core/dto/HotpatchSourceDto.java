package io.github.jdubois.bootui.core.dto;

/**
 * The source to edit for one class.
 *
 * @param path the local source file it was read from, or the class file it was decompiled from
 * @param source its content
 * @param patchedSource the source of the patch in effect for the class, or {@code null}
 * @param decompiled whether {@code source} was decompiled from bytecode, the deployment carrying no source
 */
public record HotpatchSourceDto(
        String className, String path, String source, String patchedSource, boolean decompiled) {}
