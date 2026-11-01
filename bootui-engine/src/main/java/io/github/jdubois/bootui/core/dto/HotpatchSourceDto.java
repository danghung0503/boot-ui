package io.github.jdubois.bootui.core.dto;

/**
 * The source to edit for one class.
 *
 * @param path the local source file it was read from
 * @param source its content
 * @param patchedSource the source of the patch in effect for the class, or {@code null}
 */
public record HotpatchSourceDto(String className, String path, String source, String patchedSource) {}
