package io.github.jdubois.bootui.engine.hotpatch;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/** A file manager writing class files to memory instead of disk. */
final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<String, ClassOutput> outputs = new LinkedHashMap<>();

    MemoryFileManager(StandardJavaFileManager delegate) {
        super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        return outputs.computeIfAbsent(className, ClassOutput::new);
    }

    Map<String, byte[]> classes() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        outputs.forEach((name, output) -> out.put(name, output.bytes()));
        return out;
    }
}
