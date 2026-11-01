package io.github.jdubois.bootui.engine.hotpatch;

import io.github.jdubois.bootui.core.dto.HotpatchCompileDto;
import io.github.jdubois.bootui.core.dto.HotpatchDto;
import io.github.jdubois.bootui.core.dto.HotpatchReport;
import io.github.jdubois.bootui.core.dto.HotpatchRequest;
import io.github.jdubois.bootui.core.dto.HotpatchSourceDto;
import io.github.jdubois.bootui.engine.javaagent.AgentBridgeAccess;
import io.github.jdubois.bootui.engine.javaagent.AgentClaim;
import io.github.jdubois.bootui.engine.source.ClassFileLocator;
import io.github.jdubois.bootui.engine.source.SourceLocator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Hotpatch panel (mani fork): edit an application class's local source, compile it with this JVM's compiler, and
 * redefine the class through the BootUI agent, without a restart. Only METHOD BODIES can change: the JVM refuses to add
 * or remove a method or a field or change a signature, a new lambda included. A patch lives in this JVM only and is
 * lost when the application restarts; it is a development tool, never a deployment.
 *
 * <p>Framework-neutral. The source is the class's local source file ({@link SourceLocator}), so a class compiled into an
 * archive has none. Compilation uses the running class path and the original class's release, with Lombok as annotation
 * processor when found ({@link AnnotationProcessors}).</p>
 */
public final class HotpatchService {

    public static final String REQUIREMENT = "Requires the BootUI agent and a JDK";

    private static final Pattern CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final int MAX_CLASSES = 60;

    private final AgentBridgeAccess access;
    private final Supplier<AgentClaim> claims;
    private final Supplier<String> agentUnavailable;
    private final ClassLoader loader;
    private final String classPath;
    private final Path lombok;
    /** Patches in effect, by class, oldest first, with the source they were compiled from. */
    private final Map<String, Applied> applied = new LinkedHashMap<>();

    private record Applied(HotpatchDto patch, String source) {}

    /**
     * @param access the bridge
     * @param claims this application's current claim, or a supplier of {@code null}
     * @param agentUnavailable why the agent is unusable for this application, or {@code null}
     * @param loader the application's class loader
     */
    public HotpatchService(
            AgentBridgeAccess access,
            Supplier<AgentClaim> claims,
            Supplier<String> agentUnavailable,
            ClassLoader loader) {
        this.access = access == null ? AgentBridgeAccess.absent() : access;
        this.claims = claims == null ? () -> null : claims;
        this.agentUnavailable = agentUnavailable == null ? () -> null : agentUnavailable;
        this.loader = loader;
        this.classPath = System.getProperty("java.class.path", "");
        this.lombok = AnnotationProcessors.lombok(classPath, Path.of(System.getProperty("user.home", ".")))
                .orElse(null);
    }

    /** Why patches cannot be applied, or {@code null}. */
    public String unavailableReason() {
        String agent = agentUnavailable.get();
        if (agent != null) {
            return agent;
        }
        if (!access.hotpatchesSupported()) {
            return REQUIREMENT
                    + ": the attached BootUI agent has no Hotpatch support (use the mani build of the agent).";
        }
        if (claims.get() == null) {
            return REQUIREMENT + ": this application has not claimed the BootUI agent.";
        }
        if (!PatchCompiler.available()) {
            return REQUIREMENT + ": this JVM is a JRE without a Java compiler.";
        }
        return null;
    }

    public synchronized HotpatchReport report() {
        String reason = unavailableReason();
        return new HotpatchReport(
                reason == null,
                reason,
                lombok == null ? null : lombok.toString(),
                packages(),
                applied.values().stream().map(Applied::patch).toList());
    }

    /** Application classes, top-level, whose name contains {@code query} (case-insensitive), from class directories. */
    public List<String> classes(String query) {
        String q = query == null ? "" : query.toLowerCase();
        TreeSet<String> names = new TreeSet<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            Path root = Path.of(entry);
            if (!Files.isDirectory(root)) {
                continue;
            }
            for (String pkg : packages()) {
                Path dir = root.resolve(pkg.replace('.', File.separatorChar));
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(dir, 16)) {
                    files.map(f -> root.relativize(f).toString())
                            .filter(f -> f.endsWith(".class") && !f.contains("$"))
                            .map(f -> f.substring(0, f.length() - ".class".length())
                                    .replace(File.separatorChar, '.'))
                            .filter(n -> n.toLowerCase().contains(q))
                            .forEach(names::add);
                } catch (IOException | RuntimeException ex) {
                    // An unreadable directory adds nothing.
                }
            }
        }
        return names.stream().limit(MAX_CLASSES).toList();
    }

    /** The local source file of {@code className}, and the source of its patch in effect. */
    public synchronized HotpatchSourceDto source(String className) {
        checkName(className);
        Class<?> type = type(className);
        URI classFile = ClassFileLocator.forScan().locate(className, loader, type);
        String outer = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        String simple = outer.substring(outer.lastIndexOf('.') + 1);
        SourceLocator.Result result =
                SourceLocator.resolve(List.of(new SourceLocator.Request(className, classFile, simple + ".java")));
        Optional<SourceLocator.Resolved> resolved = result.get(className);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("No local source file for " + className
                    + (result.notes().isEmpty() ? "" : ": " + String.join("; ", result.notes())));
        }
        try {
            Path path = resolved.get().path();
            Applied patch = applied.get(className);
            return new HotpatchSourceDto(
                    className,
                    path.toString(),
                    Files.readString(path, StandardCharsets.UTF_8),
                    patch == null ? null : patch.source());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read the source of " + className + ": " + ex.getMessage());
        }
    }

    public HotpatchCompileDto compile(HotpatchRequest request) {
        return compiled(request).report();
    }

    /** Compiles and redefines the class; throws {@link IllegalArgumentException} with a readable reason when refused. */
    public synchronized HotpatchDto apply(HotpatchRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("A reason is required to apply a patch.");
        }
        String reason = unavailableReason();
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
        PatchCompiler.Compiled compiled = compiled(request);
        if (!compiled.report().ok()) {
            throw new IllegalArgumentException("The patch does not compile: "
                    + String.join("; ", compiled.report().diagnostics()));
        }
        Map<String, Object> answer = claims.get().redefineClasses(compiled.classes());
        if (!"ok".equals(answer.get("status"))) {
            throw new IllegalArgumentException(String.valueOf(answer.get("reason")));
        }
        HotpatchDto patch = new HotpatchDto(
                request.className(),
                List.copyOf(compiled.classes().keySet()),
                System.currentTimeMillis(),
                request.reason().trim(),
                sha256(compiled.classes()));
        applied.remove(request.className());
        applied.put(request.className(), new Applied(patch, request.source()));
        return patch;
    }

    /** Restores the class files of a patch's classes. */
    public synchronized HotpatchDto revert(String className) {
        checkName(className);
        Applied patch = applied.get(className);
        if (patch == null) {
            throw new IllegalArgumentException("No patch in effect for " + className + ".");
        }
        AgentClaim claim = claims.get();
        if (claim == null) {
            throw new IllegalArgumentException(REQUIREMENT + ": this application has not claimed the BootUI agent.");
        }
        Map<String, Object> answer = claim.restoreClasses(patch.patch().classes());
        if (!"ok".equals(answer.get("status"))) {
            throw new IllegalArgumentException(String.valueOf(answer.get("reason")));
        }
        applied.remove(className);
        return patch.patch();
    }

    private PatchCompiler.Compiled compiled(HotpatchRequest request) {
        if (request == null || request.source() == null || request.source().isBlank()) {
            throw new IllegalArgumentException("A source is required.");
        }
        checkName(request.className());
        return new PatchCompiler(classPath, lombok)
                .compile(request.className(), request.source(), release(request.className()));
    }

    private List<String> packages() {
        AgentClaim claim = claims.get();
        return claim == null ? List.of() : claim.packages();
    }

    private void checkName(String className) {
        if (className == null
                || className.length() > 300
                || !CLASS_NAME.matcher(className).matches()) {
            throw new IllegalArgumentException("Invalid class name: " + className);
        }
        if (packages().stream().noneMatch(p -> className.startsWith(p + "."))) {
            throw new IllegalArgumentException(className + " is not in the application's packages " + packages());
        }
    }

    private Class<?> type(String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            throw new IllegalArgumentException("Class " + className + " is not on the application class path.");
        }
    }

    /** The class file's release: major version 69 is Java 25. */
    private int release(String className) {
        try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
            if (in == null) {
                throw new IllegalArgumentException("No class file for " + className + ".");
            }
            byte[] header = in.readNBytes(8);
            return (((header[6] & 0xff) << 8) | (header[7] & 0xff)) - 44;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot read the class file of " + className + ".");
        }
    }

    private static String sha256(Map<String, byte[]> classes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            new ArrayList<>(classes.keySet()).stream().sorted().forEach(name -> {
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update(classes.get(name));
            });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
