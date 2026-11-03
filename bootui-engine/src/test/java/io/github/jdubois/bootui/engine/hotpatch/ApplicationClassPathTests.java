package io.github.jdubois.bootui.engine.hotpatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationClassPathTests {

    @TempDir
    Path dir;

    @Test
    void thinJarFromJarmodeToolsExtractListsItsClassesAndFollowsItsClassPath() throws Exception {
        Path lib = Files.createDirectories(dir.resolve("app/lib"));
        Path spring = jar(lib.resolve("spring-core.jar"), null, Map.of("org/springframework/core/Ordered.class", "o"));
        Path app = jar(
                dir.resolve("app/app.jar"),
                "lib/spring-core.jar",
                Map.of("demo/Fee.class", "fee", "demo/Fee$Rate.class", "rate"));

        ApplicationClassPath classPath = ApplicationClassPath.of(null, app.toString());

        assertThat(classPath.compilerClassPath().split(File.pathSeparator))
                .containsExactly(app.toString(), spring.toString());
        assertThat(classPath.classFile("demo.Fee", List.of("demo"))).hasContent("fee");
        assertThat(classPath.classRoots(List.of("demo")))
                .singleElement()
                .satisfies(
                        root -> assertThat(root.resolve("demo/Fee$Rate.class")).exists());
        assertThat(classPath.classRoots(List.of("other"))).isEmpty();
    }

    @Test
    void unpackedJarRunWithJarLauncherReadsTheLoaderUrls() throws Exception {
        Path unpacked = dir.resolve("unpacked");
        Path classes = Files.createDirectories(unpacked.resolve("BOOT-INF/classes/demo"));
        Files.writeString(classes.resolve("Fee.class"), "fee");
        Path lib = jar(
                Files.createDirectories(unpacked.resolve("BOOT-INF/lib")).resolve("spring-core.jar"),
                null,
                Map.of("org/springframework/core/Ordered.class", "o"));
        URL[] urls = {
            unpacked.resolve("BOOT-INF/classes").toUri().toURL(), lib.toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            // java.class.path is only the unpacked directory when run with JarLauncher
            ApplicationClassPath classPath = ApplicationClassPath.of(loader, unpacked.toString());

            assertThat(classPath.compilerClassPath().split(File.pathSeparator))
                    .contains(unpacked.resolve("BOOT-INF/classes").toString(), lib.toString());
            assertThat(classPath.classRoots(List.of("demo"))).containsExactly(unpacked.resolve("BOOT-INF/classes"));
            assertThat(classPath.classFile("demo.Fee", List.of("demo"))).hasContent("fee");
        }
    }

    private static Path jar(Path file, String classPath, Map<String, String> entries) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classPath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        }
        try (OutputStream out = Files.newOutputStream(file);
                JarOutputStream jar = new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jar.putNextEntry(new ZipEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes());
                jar.closeEntry();
            }
        }
        return file.toAbsolutePath().normalize();
    }
}
