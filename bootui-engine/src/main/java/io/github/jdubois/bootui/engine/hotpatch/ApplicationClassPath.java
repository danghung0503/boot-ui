package io.github.jdubois.bootui.engine.hotpatch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * The application's class path when it does not run from a Spring Boot executable jar ({@link ApplicationArchive} covers
 * {@code java -jar app.jar}): the launch an image uses after unpacking the jar. It is read from the application class
 * loader, not only {@code java.class.path}:
 *
 * <ul>
 *   <li>an unpacked jar run with {@code JarLauncher} (layertools, Buildpacks): the loader's URLs, {@code BOOT-INF/classes/}
 *       and {@code BOOT-INF/lib/*.jar}, while {@code java.class.path} is only the unpacked directory;</li>
 *   <li>{@code -Djarmode=tools extract}, run as {@code java -jar app/app.jar}: a thin jar whose manifest
 *       {@code Class-Path} lists {@code lib/*.jar}, with the application classes inside the thin jar;</li>
 *   <li>a plain class path ({@code java -cp classes:lib/*}, an IDE).</li>
 * </ul>
 *
 * Directories holding the application packages are class roots as they are; a jar holding them has those packages
 * extracted once to a private temporary directory, so classes can be listed and decompiled from files.
 */
final class ApplicationClassPath {

    private final List<Path> entries;
    /** Extracted class roots by jar and packages; {@code null} when the jar holds none of them. */
    private final Map<String, Path> extracted = new LinkedHashMap<>();

    private ApplicationClassPath(List<Path> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The class path of {@code loader} and its parents, then {@code javaClassPath}, with jar manifests' Class-Path. */
    static ApplicationClassPath of(ClassLoader loader, String javaClassPath) {
        Set<Path> paths = new LinkedHashSet<>();
        List<ClassLoader> chain = new ArrayList<>();
        for (ClassLoader l = loader; l != null; l = l.getParent()) {
            chain.add(l);
        }
        Collections.reverse(chain); // parents first, as the loaders delegate
        for (ClassLoader l : chain) {
            if (l instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    file(url).ifPresent(paths::add);
                }
            }
        }
        if (javaClassPath != null) {
            for (String entry : javaClassPath.split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    paths.add(Path.of(entry).toAbsolutePath().normalize());
                }
            }
        }
        List<Path> ordered = new ArrayList<>();
        for (Path path : paths) {
            add(path, ordered, 0);
        }
        return new ApplicationClassPath(ordered);
    }

    List<Path> entries() {
        return entries;
    }

    /** The entries as a compiler class path. */
    String compilerClassPath() {
        List<String> out = new ArrayList<>();
        for (Path entry : entries) {
            out.add(entry.toString());
        }
        return String.join(File.pathSeparator, out);
    }

    /**
     * Directories whose files are the application classes under {@code packages}: class directories as they are, and
     * jars holding those packages extracted to a temporary directory once.
     */
    synchronized List<Path> classRoots(List<String> packages) {
        List<Path> roots = new ArrayList<>();
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                if (packages.stream().anyMatch(p -> Files.isDirectory(entry.resolve(p.replace('.', '/'))))) {
                    roots.add(entry);
                }
            } else if (Files.isRegularFile(entry)) {
                Path root = extracted(entry, packages);
                if (root != null) {
                    roots.add(root);
                }
            }
        }
        return roots;
    }

    /** The class file of top-level {@code className} under the class roots, or {@code null}. */
    Path classFile(String className, List<String> packages) {
        String relative = className.replace('.', '/') + ".class";
        for (Path root : classRoots(packages)) {
            Path file = root.resolve(relative);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private Path extracted(Path jar, List<String> packages) {
        String key = jar + "|" + String.join(",", packages);
        if (extracted.containsKey(key)) {
            return extracted.get(key);
        }
        Path root = null;
        List<String> prefixes =
                packages.stream().map(p -> p.replace('.', '/') + "/").toList();
        try (JarFile file = new JarFile(jar.toFile())) {
            List<ZipEntry> classes = file.stream()
                    .filter(e -> !e.isDirectory()
                            && e.getName().endsWith(".class")
                            && prefixes.stream().anyMatch(e.getName()::startsWith))
                    .map(e -> (ZipEntry) e)
                    .toList();
            if (!classes.isEmpty()) {
                root = Files.createTempDirectory("bootui-hotpatch-classes-");
                for (ZipEntry entry : classes) {
                    Path out = root.resolve(entry.getName()).normalize();
                    if (!out.startsWith(root)) {
                        throw new IOException("Archive entry outside the extraction directory: " + entry.getName());
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream in = file.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read the application classes in " + jar, ex);
        }
        extracted.put(key, root);
        return root;
    }

    /** {@code path}, then the jars its manifest's Class-Path names (as javac follows them), depth-limited. */
    private static void add(Path path, List<Path> out, int depth) {
        if (out.contains(path) || !Files.exists(path)) {
            return;
        }
        out.add(path);
        if (depth > 2
                || !Files.isRegularFile(path)
                || !path.getFileName().toString().endsWith(".jar")) {
            return;
        }
        try (JarFile jar = new JarFile(path.toFile())) {
            Manifest manifest = jar.getManifest();
            String classPath =
                    manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath == null) {
                return;
            }
            for (String item : classPath.trim().split("\\s+")) {
                try {
                    Path next = item.startsWith("file:")
                            ? Path.of(java.net.URI.create(item))
                            : path.getParent().resolve(item.replace('/', File.separatorChar));
                    add(next.toAbsolutePath().normalize(), out, depth + 1);
                } catch (IllegalArgumentException ex) {
                    // An entry that is no local file adds nothing.
                }
            }
        } catch (IOException ex) {
            // An unreadable jar keeps its own entry only.
        }
    }

    private static java.util.Optional<Path> file(URL url) {
        if (!"file".equals(url.getProtocol())) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Path.of(url.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }
}
