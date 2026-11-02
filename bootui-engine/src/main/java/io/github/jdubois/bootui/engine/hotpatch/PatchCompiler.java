package io.github.jdubois.bootui.engine.hotpatch;

import io.github.jdubois.bootui.core.dto.HotpatchCompileDto;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
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
 * Compiles patched source in memory, against the running class path and the original class's release, with javac: this
 * JVM's on a JDK, else the JDK's {@code jdk.compiler} classes bundled in the application on a JRE (runtime images are
 * JREs). Only javac will do: the JVM redefines a class only when the new bytecode declares the same methods and fields,
 * and another compiler names synthetic members (lambda methods, switch tables) differently. Lombok is the annotation
 * processor when found.
 */
final class PatchCompiler {

    /** A compilation's report and the bytecode of the class and its nested classes, empty unless it compiled. */
    record Compiled(HotpatchCompileDto report, Map<String, byte[]> classes) {}

    private final String classPath;
    private final Path lombok;
    private final JavaCompiler compiler;

    PatchCompiler(String classPath, Path lombok) {
        this(classPath, lombok, compiler());
    }

    /** With {@code compiler} instead of this JVM's choice. */
    PatchCompiler(String classPath, Path lombok, JavaCompiler compiler) {
        this.classPath = classPath;
        this.lombok = lombok;
        this.compiler = compiler;
    }

    static boolean available() {
        return compiler() != null;
    }

    /** {@code javac <version>} on a JDK, {@code javac <version> (bundled)} on a JRE, or {@code null} when there is none. */
    static String name() {
        if (ToolProvider.getSystemJavaCompiler() != null) {
            return "javac " + Runtime.version();
        }
        return bundled() == null ? null : "javac " + Runtime.version() + " (bundled)";
    }

    /** This JVM's javac, else the javac bundled in the application. */
    private static JavaCompiler compiler() {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        return javac != null ? javac : bundled();
    }

    /** {@code jdk.compiler}'s classes on the application class path (unnamed module), as on a JRE. */
    static JavaCompiler bundled() {
        try {
            return (JavaCompiler) Class.forName("com.sun.tools.javac.api.JavacTool")
                    .getMethod("create")
                    .invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /**
     * {@code --release} needs the JDK's {@code lib/ct.sym}, which a JRE lacks: there the class compiles with
     * {@code -source}/{@code -target} against the running platform's own modules, the ones it runs on.
     */
    static List<String> releaseOptions(int release, boolean ctSym) {
        String version = String.valueOf(release);
        return ctSym ? List.of("--release", version) : List.of("-source", version, "-target", version);
    }

    Compiled compile(String className, String source, int release) {
        JavaCompiler javac = compiler;
        if (javac == null) {
            return failure("this JVM has no Java compiler: run the application on a JDK, or bundle the JDK's javac"
                    + " (jdk.compiler) in it");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean ctSym = Files.isRegularFile(Path.of(System.getProperty("java.home"), "lib", "ct.sym"));
        List<String> options = new ArrayList<>(releaseOptions(release, ctSym));
        options.addAll(List.of("-nowarn", "-g", "-parameters"));
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
