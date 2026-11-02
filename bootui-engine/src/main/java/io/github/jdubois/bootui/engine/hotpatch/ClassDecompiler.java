package io.github.jdubois.bootui.engine.hotpatch;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

/**
 * Java source for a class compiled into the deployment, with Vineflower, when no source file exists: deployments carry
 * bytecode only. Generic types come back from the class's signatures (local variables included, the class being
 * compiled with {@code -g}), and Lombok and other annotation processors are already expanded in the bytecode (getters,
 * constructors, loggers), so the result compiles without them. Comments are lost.
 */
final class ClassDecompiler {

    private static final Pattern IMPORT = Pattern.compile("(?m)^import ([\\w.]+)\\.(\\w+);\\R");

    private ClassDecompiler() {}

    /**
     * Drops the imports, and the annotations, whose type is absent from the application's class path: compile-only
     * annotations kept in the bytecode, such as Lombok's {@code @lombok.Generated} (CLASS retention), which the
     * deployment does not ship. An annotation whose type is absent has no effect at run time, so dropping it changes
     * nothing; any other use of a missing type still fails to compile, as it should.
     *
     * @param present whether a binary or canonical type name resolves on the application's class path
     */
    static String withoutMissingTypes(String java, Predicate<String> present) {
        StringBuilder kept = new StringBuilder();
        List<String[]> missing = new ArrayList<>();
        Matcher imports = IMPORT.matcher(java);
        while (imports.find()) {
            String type = imports.group(1) + "." + imports.group(2);
            if (!present.test(type)) {
                missing.add(new String[] {type, imports.group(2)});
                imports.appendReplacement(kept, "");
            }
        }
        imports.appendTail(kept);
        String result = kept.toString();
        for (String[] type : missing) {
            for (String name : List.of(type[0], type[1])) {
                result = result.replaceAll(
                        "@" + Pattern.quote(name) + "(?![\\w.$])(\\([^()]*\\))?[ \\t]*(\\R[ \\t]*)?", "");
            }
        }
        return result;
    }

    /**
     * @param classFile the top-level class file; its nested classes ({@code Outer$*.class}) are read from the same directory
     * @param classPath the class path the class's types resolve against
     */
    static String decompile(Path classFile, String classPath) {
        String outer = classFile.getFileName().toString().replaceFirst("\\.class$", "");
        List<File> inputs = new ArrayList<>();
        try (Stream<Path> siblings = Files.list(classFile.getParent())) {
            siblings.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.equals(outer + ".class")
                                || (name.startsWith(outer + "$") && name.endsWith(".class"));
                    })
                    .forEach(p -> inputs.add(p.toFile()));
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read the class files next to " + classFile, ex);
        }
        List<File> libraries = Stream.of(classPath.split(File.pathSeparator))
                .filter(entry -> !entry.isBlank())
                .map(File::new)
                .filter(File::exists)
                .toList();
        JavaSaver saver = new JavaSaver();
        Decompiler.builder()
                .inputs(inputs.toArray(File[]::new))
                .libraries(libraries.toArray(File[]::new))
                .output(saver)
                .logger(IFernflowerLogger.NO_OP)
                .build()
                .decompile();
        if (saver.java == null) {
            throw new IllegalArgumentException("Cannot decompile " + classFile.getFileName());
        }
        return saver.java;
    }

    /** Keeps the one top-level class's source; nested classes are written inside it. */
    private static final class JavaSaver implements IResultSaver {

        private String java;

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            if (content != null && !qualifiedName.contains("$")) {
                java = content;
            }
        }

        @Override
        public void saveFolder(String path) {}

        @Override
        public void copyFile(String source, String path, String entryName) {}

        @Override
        public void createArchive(String path, String archiveName, Manifest manifest) {}

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {}

        @Override
        public void copyEntry(String source, String path, String archiveName, String entry) {}

        @Override
        public void saveClassEntry(
                String path, String archiveName, String qualifiedName, String entryName, String content) {}

        @Override
        public void closeArchive(String path, String archiveName) {}
    }
}
