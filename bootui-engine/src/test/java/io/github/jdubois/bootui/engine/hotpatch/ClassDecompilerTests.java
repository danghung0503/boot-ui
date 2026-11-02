package io.github.jdubois.bootui.engine.hotpatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassDecompilerTests {

    private static final String CLASS_PATH = System.getProperty("java.class.path");

    @Test
    void decompiledSourceRecompilesToTheSameClasses(@TempDir Path dir) throws Exception {
        PatchCompiler compiler = new PatchCompiler(CLASS_PATH, null);
        PatchCompiler.Compiled original = compiler.compile("demo.Fee", """
                package demo;
                import java.util.List;
                public class Fee {
                    private final List<Long> amounts;
                    public Fee(List<Long> amounts) { this.amounts = amounts; }
                    // comments do not survive
                    long total() { return amounts.stream().mapToLong(a -> a == null ? 0 : a * 2 / 100).sum(); }
                    long kept() {
                        List<Long> kept = List.of();
                        try { kept = amounts.stream().filter(a -> a != null).sorted().toList(); }
                        catch (RuntimeException e) { return -1; }
                        return kept.stream().mapToLong(Long::longValue).sum();
                    }
                    static class Rate { int percent() { return 2; } }
                }
                """, 17);
        for (Map.Entry<String, byte[]> entry : original.classes().entrySet()) {
            Path file = dir.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        String source = ClassDecompiler.decompile(dir.resolve("demo/Fee.class"), dir + File.pathSeparator + CLASS_PATH);

        assertThat(source)
                .contains("package demo;", "class Fee", "long total()", "class Rate", "List<Long> kept")
                .doesNotContain("comments");
        PatchCompiler.Compiled again = compiler.compile("demo.Fee", source, 17);
        assertThat(again.report().ok())
                .as(String.join("\n", again.report().diagnostics()))
                .isTrue();
        assertThat(again.classes()).containsOnlyKeys(original.classes().keySet());
    }

    @Test
    void dropsAnnotationsWhoseTypeTheDeploymentDoesNotShip() {
        String java = """
                package demo;

                import java.util.List;
                import lombok.Generated;
                import org.example.Missing;

                public class Fee {
                   private final List<Long> amounts;

                   @Generated
                   public Fee(@org.example.Missing("x") List<Long> amounts) {
                      this.amounts = amounts;
                   }

                   @Generated
                   @Deprecated
                   long total() {
                      return 0L;
                   }
                }
                """;

        String kept = ClassDecompiler.withoutMissingTypes(java, type -> type.startsWith("java."));

        assertThat(kept)
                .contains("import java.util.List;", "public Fee(List<Long> amounts)", "@Deprecated")
                .doesNotContain("lombok", "Generated", "Missing")
                .contains("\n   public Fee(", "\n   @Deprecated\n   long total()");
    }
}
