package io.github.jdubois.bootui.agent;

import io.github.jdubois.bootui.agent.bridge.AgentBridge;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hotpatch panel's agent side (mani fork): redefines application classes of the current run with new bytecode, and
 * restores them from the class files they were loaded from. Only method BODIES can change: the JVM refuses to add or
 * remove a method, a field, or change a signature, so a refusal is answered, never thrown. The agent's own
 * transformers run on the new bytecode as on a class load, so its sensors keep instrumenting a patched class.
 *
 * <p>A class is looked up only among the classes the current run's class loaders defined, so a patch never reaches
 * BootUI, the JDK, or another application in the JVM. The original bytecode is read once, before the first patch of a
 * class, from the class loader's resource for it.</p>
 */
final class ClassRedefiner {

    private final Instrumentation instrumentation;
    /** Original bytecode per class name, captured before the first patch; dropped on restore. */
    private final Map<String, byte[]> originals = new HashMap<String, byte[]>();

    ClassRedefiner(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /**
     * {@code action} {@code redefine} with {@code classes} (binary name to bytecode), or {@code restore} with
     * {@code classNames}: answers {@code ok} with the {@code classes} it changed, or {@code failed} with a reason.
     */
    synchronized Map<String, Object> apply(Map<String, Object> request, List<WeakReference<ClassLoader>> loaders) {
        if (!instrumentation.isRedefineClassesSupported()) {
            return answer(AgentBridge.UNAVAILABLE, "this JVM does not support class redefinition", null);
        }
        String action = String.valueOf(request.get("action"));
        try {
            if ("redefine".equals(action)) {
                return redefine(bytecode(request.get("classes")), loaders);
            }
            if ("restore".equals(action)) {
                return restore(names(request.get("classNames")), loaders);
            }
            return answer(AgentBridge.FAILED, "unknown hotpatch action " + action, null);
        } catch (Throwable ex) {
            return answer(AgentBridge.FAILED, "the JVM refused the change: " + ex, null);
        }
    }

    private Map<String, Object> redefine(Map<String, byte[]> classes, List<WeakReference<ClassLoader>> loaders)
            throws Exception {
        Map<String, Class<?>> loaded = loaded(classes.keySet(), loaders);
        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        Map<String, byte[]> captured = new HashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Class<?> type = loaded.get(entry.getKey());
            if (!originals.containsKey(entry.getKey())) {
                captured.put(entry.getKey(), original(type));
            }
            definitions.add(new ClassDefinition(type, entry.getValue()));
        }
        instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
        originals.putAll(captured);
        return answer("ok", null, new ArrayList<String>(classes.keySet()));
    }

    private Map<String, Object> restore(List<String> names, List<WeakReference<ClassLoader>> loaders) throws Exception {
        Map<String, Class<?>> loaded = loaded(names, loaders);
        List<ClassDefinition> definitions = new ArrayList<ClassDefinition>();
        for (String name : names) {
            byte[] original = originals.get(name);
            definitions.add(
                    new ClassDefinition(loaded.get(name), original != null ? original : original(loaded.get(name))));
        }
        instrumentation.redefineClasses(definitions.toArray(new ClassDefinition[0]));
        for (String name : names) {
            originals.remove(name);
        }
        return answer("ok", null, names);
    }

    private Map<String, Class<?>> loaded(Collection<String> names, List<WeakReference<ClassLoader>> loaders) {
        Map<String, Class<?>> found = new LinkedHashMap<String, Class<?>>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (names.contains(type.getName()) && runLoader(type.getClassLoader(), loaders)) {
                found.put(type.getName(), type);
            }
        }
        for (String name : names) {
            if (!found.containsKey(name)) {
                throw new IllegalArgumentException(
                        name + " is not loaded by this application (a new nested or anonymous"
                                + " class, or a class not used yet): only loaded classes can be patched");
            }
        }
        return found;
    }

    private static boolean runLoader(ClassLoader loader, List<WeakReference<ClassLoader>> loaders) {
        if (loader == null) {
            return false;
        }
        for (WeakReference<ClassLoader> reference : loaders) {
            if (reference.get() == loader) {
                return true;
            }
        }
        return false;
    }

    private static byte[] original(Class<?> type) throws IOException {
        InputStream in =
                type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class");
        if (in == null) {
            throw new IOException("no class file for " + type.getName());
        }
        try {
            return in.readAllBytes();
        } finally {
            in.close();
        }
    }

    private static Map<String, byte[]> bytecode(Object value) {
        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (entry.getValue() instanceof byte[]) {
                    classes.put(String.valueOf(entry.getKey()), (byte[]) entry.getValue());
                }
            }
        }
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("no class to redefine");
        }
        return classes;
    }

    private static List<String> names(Object value) {
        List<String> names = new ArrayList<String>();
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                names.add(String.valueOf(item));
            }
        }
        return names;
    }

    private static Map<String, Object> answer(String status, String reason, List<String> classes) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        map.put("classes", classes);
        return map;
    }
}
