package io.github.jdubois.bootui.engine.hotpatch;

import io.github.jdubois.bootui.core.dto.HotpatchCompileDto;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Compiles patched source in memory with this JVM's compiler, against the running class path and the original class's
 * release, with Lombok as annotation processor when found.
 */
final class PatchCompiler {

    /** A compilation's report and the bytecode of the class and its nested classes, empty unless it compiled. */
    record Compiled(HotpatchCompileDto report, Map<String, byte[]> classes) {}

    private final String classPath;
    private final Path lombok;

    PatchCompiler(String classPath, Path lombok) {
        this.classPath = classPath;
        this.lombok = lombok;
    }

    static boolean available() {
        return ToolProvider.getSystemJavaCompiler() != null;
    }

    Compiled compile(String className, String source, int release) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return failure("this JVM has no Java compiler (a JRE): run the application on a JDK");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options =
                new ArrayList<>(List.of("--release", String.valueOf(release), "-nowarn", "-g", "-parameters"));
        if (lombok != null) {
            options.addAll(List.of(
                    "-classpath",
                    classPath + File.pathSeparator + lombok,
                    "-processorpath",
                    lombok.toString(),
                    "-proc:full"));
        } else {
            options.addAll(List.of("-classpath", classPath, "-proc:none"));
        }
        try (MemoryFileManager files =
                new MemoryFileManager(javac.getStandardFileManager(diagnostics, Locale.ROOT, null))) {
            boolean ok = javac.getTask(
                            null, files, diagnostics, options, null, List.of(new SourceFile(className, source)))
                    .call();
            List<String> messages = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.NOTE && d.getKind() != Diagnostic.Kind.OTHER) {
                    messages.add((d.getLineNumber() > 0 ? "line " + d.getLineNumber() + ": " : "")
                            + d.getMessage(Locale.ROOT));
                }
            }
            Map<String, byte[]> own = new LinkedHashMap<>();
            if (ok) {
                files.classes().forEach((name, bytes) -> {
                    if (name.equals(className) || name.startsWith(className + "$")) {
                        own.put(name, bytes);
                    }
                });
                if (!own.containsKey(className)) {
                    return failure("the source does not declare " + className);
                }
            }
            return new Compiled(new HotpatchCompileDto(ok, List.copyOf(messages)), ok ? own : Map.of());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Compiled failure(String message) {
        return new Compiled(new HotpatchCompileDto(false, List.of(message)), Map.of());
    }
}
