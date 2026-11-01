package io.github.jdubois.bootui.engine.hotpatch;

import java.net.URI;
import javax.tools.SimpleJavaFileObject;

/** Source held in memory for the compiler. */
final class SourceFile extends SimpleJavaFileObject {

    private final String source;

    SourceFile(String className, String source) {
        super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
        this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
    }
}
