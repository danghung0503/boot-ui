package io.github.jdubois.bootui.core.dto;

/**
 * Compiles or applies patched source for one class.
 *
 * @param reason why the patch is applied: required to apply, ignored to compile
 */
public record HotpatchRequest(String className, String source, String reason) {}
