package io.github.jdubois.bootui.engine.hotpatch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The executable Spring Boot jar the application runs from ({@code java -jar app.jar}), the only input Hotpatch has when the
 * deployment carries no source: its {@code BOOT-INF/classes} and {@code BOOT-INF/lib/*.jar} are extracted once, on first use,
 * to a private temporary directory, which then serves as the compiler's class path, the class list, and the class files to
 * decompile. javac cannot read the jar's nested jars directly.
 */
final class ApplicationArchive {

    private static final String CLASSES = "BOOT-INF/classes/";
    private static final String LIB = "BOOT-INF/lib/";

    private final Path jar;
    private Path root;

    private ApplicationArchive(Path jar) {
        this.jar = jar;
    }

    /** The executable jar when {@code classPath} is exactly one jar laid out as a Spring Boot executable archive. */
    static Optional<ApplicationArchive> of(String classPath) {
        if (classPath == null || classPath.isBlank() || classPath.contains(File.pathSeparator)) {
            return Optional.empty();
        }
        Path jar = Path.of(classPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(CLASSES) != null
                            || zip.stream().anyMatch(e -> e.getName().startsWith(CLASSES))
                    ? Optional.of(new ApplicationArchive(jar))
                    : Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    Path jar() {
        return jar;
    }

    /** The extracted {@code BOOT-INF/classes} directory. */
    synchronized Path classes() {
        return extracted().resolve(CLASSES);
    }

    /** The extracted classes then every nested library, as a compiler class path. */
    synchronized String classPath() {
        List<String> entries = new ArrayList<>();
        entries.add(classes().toString());
        Path lib = extracted().resolve(LIB);
        if (Files.isDirectory(lib)) {
            try (Stream<Path> jars = Files.list(lib)) {
                jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .forEach(p -> entries.add(p.toString()));
            } catch (IOException ex) {
                throw new UncheckedIOException("Cannot list the libraries of " + jar, ex);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    private Path extracted() {
        if (root != null) {
            return root;
        }
        try {
            Path dir = Files.createTempDirectory("bootui-hotpatch-");
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !(name.startsWith(CLASSES) || name.startsWith(LIB))) {
                        continue;
                    }
                    Path out = dir.resolve(name).normalize();
                    if (!out.startsWith(dir)) {
                        throw new IOException("Archive entry outside the extraction directory: " + name);
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            root = dir;
            return root;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot extract " + jar, ex);
        }
    }
}
