package io.github.jdubois.bootui.engine.hotpatch;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Finds the Lombok jar a patch is compiled with: source files use Lombok annotations whose processor is a compile-only
 * dependency, absent from the running class path. Looks on the class path first, then in the local Gradle and Maven
 * caches, newest version first.
 */
final class AnnotationProcessors {

    private AnnotationProcessors() {}

    static Optional<Path> lombok(String classPath, Path home) {
        for (String entry : classPath.split(File.pathSeparator)) {
            Path p = Path.of(entry);
            if (lombokJar(p) && Files.isRegularFile(p)) {
                return Optional.of(p);
            }
        }
        Optional<Path> gradle = newest(home.resolve(".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok"), 3);
        return gradle.isPresent() ? gradle : newest(home.resolve(".m2/repository/org/projectlombok/lombok"), 2);
    }

    private static Optional<Path> newest(Path root, int depth) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.find(root, depth, (p, a) -> a.isRegularFile() && lombokJar(p))) {
            return files.max(Comparator.comparing(p -> version(p.getFileName().toString())));
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static boolean lombokJar(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return name.startsWith("lombok-")
                && name.endsWith(".jar")
                && !name.contains("sources")
                && !name.contains("javadoc");
    }

    /** {@code lombok-1.18.42.jar} as a sortable key: each number zero-padded. */
    private static String version(String fileName) {
        String v = fileName.substring("lombok-".length(), fileName.length() - ".jar".length());
        StringBuilder key = new StringBuilder();
        for (String part : v.split("[.-]")) {
            key.append(part.chars().allMatch(Character::isDigit) ? String.format("%08d", Long.parseLong(part)) : part)
                    .append('.');
        }
        return key.toString();
    }
}
