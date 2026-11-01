package io.github.jdubois.bootui.engine.hotpatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchCompilerTests {

    private final PatchCompiler compiler = new PatchCompiler(System.getProperty("java.class.path"), null);

    @Test
    void compilesTheClassAndItsNestedClassesInMemory() {
        PatchCompiler.Compiled compiled = compiler.compile(
                "demo.Fee",
                "package demo; public class Fee { long fee(Long a) { return a == null ? 0 : a * 2 / 100; } static class Rate {} }",
                17);

        assertThat(compiled.report().ok()).isTrue();
        assertThat(compiled.classes()).containsOnlyKeys("demo.Fee", "demo.Fee$Rate");
        assertThat(compiled.classes().get("demo.Fee")[7]).isEqualTo((byte) 61); // release 17 = major 61
    }

    @Test
    void reportsErrorsWithLineNumbersAndNoBytecode() {
        PatchCompiler.Compiled compiled =
                compiler.compile("demo.Fee", "package demo;\npublic class Fee { long x() { return y; } }", 17);

        assertThat(compiled.report().ok()).isFalse();
        assertThat(compiled.report().diagnostics()).singleElement().asString().startsWith("line 2:");
        assertThat(compiled.classes()).isEmpty();
    }

    @Test
    void refusesSourceThatDeclaresAnotherClass() {
        PatchCompiler.Compiled compiled = compiler.compile("demo.Fee", "package demo; public class Other {}", 17);

        assertThat(compiled.report().ok()).isFalse();
    }

    @Test
    void findsTheNewestLombokInTheGradleCache(@TempDir Path home) throws Exception {
        Path cache = home.resolve(".gradle/caches/modules-2/files-2.1/org.projectlombok/lombok");
        for (String version : new String[] {"1.18.9", "1.18.42", "1.18.30"}) {
            Path jar = cache.resolve(version).resolve("hash").resolve("lombok-" + version + ".jar");
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "");
            Files.writeString(jar.resolveSibling("lombok-" + version + "-sources.jar"), "");
        }

        assertThat(AnnotationProcessors.lombok("", home)).get().asString().endsWith("lombok-1.18.42.jar");
        assertThat(AnnotationProcessors.lombok("", home.resolve("empty"))).isEmpty();
    }
}
