package io.github.jdubois.bootui.engine.hotpatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationArchiveTests {

    @TempDir
    Path dir;

    @Test
    void recognizesOnlyASingleSpringBootExecutableJar() throws Exception {
        Path boot = jar("app.jar", Map.of("BOOT-INF/classes/demo/Fee.class", "x"));
        Path plain = jar("plain.jar", Map.of("demo/Fee.class", "x"));

        assertThat(ApplicationArchive.of(boot.toString())).isPresent();
        assertThat(ApplicationArchive.of(plain.toString())).isEmpty();
        assertThat(ApplicationArchive.of(boot + File.pathSeparator + plain)).isEmpty();
        assertThat(ApplicationArchive.of(dir.toString())).isEmpty();
        assertThat(ApplicationArchive.of("")).isEmpty();
    }

    @Test
    void extractsItsClassesAndLibrariesAsTheCompilerClassPath() throws Exception {
        Path boot = jar(
                "app.jar",
                Map.of(
                        "BOOT-INF/classes/demo/Fee.class", "fee",
                        "BOOT-INF/lib/b.jar", "b",
                        "BOOT-INF/lib/a.jar", "a",
                        "org/springframework/boot/loader/Launcher.class", "loader"));

        ApplicationArchive archive = ApplicationArchive.of(boot.toString()).orElseThrow();

        assertThat(archive.classes().resolve("demo/Fee.class")).hasContent("fee");
        assertThat(archive.classPath().split(File.pathSeparator))
                .containsExactly(
                        archive.classes().toString(),
                        archive.classes().resolveSibling("lib").resolve("a.jar").toString(),
                        archive.classes().resolveSibling("lib").resolve("b.jar").toString());
        assertThat(archive.classes().getParent().resolveSibling("org")).doesNotExist(); // only BOOT-INF/classes and lib
    }

    private Path jar(String name, Map<String, String> entries) throws Exception {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes());
                zip.closeEntry();
            }
        }
        return jar;
    }
}
