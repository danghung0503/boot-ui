package io.github.jdubois.bootui.engine.hotpatch;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import javax.tools.SimpleJavaFileObject;

/** Bytecode the compiler writes, held in memory. */
final class ClassOutput extends SimpleJavaFileObject {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    ClassOutput(String className) {
        super(URI.create("mem:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
    }

    @Override
    public OutputStream openOutputStream() {
        return bytes;
    }

    byte[] bytes() {
        return bytes.toByteArray();
    }
}
