package io.github.jdubois.bootui.engine.javaagent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Loads the BootUI agent into this running JVM when it was not started with {@code -javaagent} (mani fork): the agent jar
 * ships inside BootUI's own jar as {@value #AGENT_RESOURCE}, so an application only adds that one jar. A JRE has no
 * {@code jdk.attach} module, so the agent is loaded with the JVM's {@code JVMTI.agent_load} diagnostic command, reached
 * through the platform MBean server ({@code jdk.management}), which JREs ship. The JVM then appends the agent jar to the
 * system class path and calls its {@code Agent-Class}, as a dynamic attach would; recent JVMs print a warning about
 * dynamically loaded agents unless started with {@code -XX:+EnableDynamicAgentLoading}.
 */
public final class AgentSelfAttach {

    /** The agent jar inside BootUI's jar, written by the single-jar build ({@code bootui-hotpatch}). */
    public static final String AGENT_RESOURCE = "/META-INF/bootui/bootui-agent.jar";

    private static final List<String> INSTRUMENT_LIBRARIES =
            List.of("lib/libinstrument.so", "lib/libinstrument.dylib", "bin/instrument.dll");

    private AgentSelfAttach() {}

    /** Whether this BootUI jar carries the agent. */
    public static boolean available() {
        return AgentSelfAttach.class.getResource(AGENT_RESOURCE) != null;
    }

    /**
     * Loads the carried agent unless its bridge is already on the bootstrap class path.
     *
     * @return {@code null} when the bridge is present afterwards, else why not
     */
    public static synchronized String attach() {
        if (AgentBridgeAccess.locate().present()) {
            return null;
        }
        try (InputStream in = AgentSelfAttach.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) {
                return "this BootUI jar carries no agent";
            }
            Path home = Path.of(System.getProperty("java.home"));
            Path library = INSTRUMENT_LIBRARIES.stream()
                    .map(home::resolve)
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
            if (library == null) {
                return "this JVM has no java.instrument library";
            }
            // The JVM keeps the jar open and on the system class path for its lifetime.
            Path jar = Files.createTempFile("bootui-agent-", ".jar");
            Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
            jar.toFile().deleteOnExit();
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object answer = server.invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "jvmtiAgentLoad",
                    new Object[] {new String[] {library.toString(), jar.toString()}},
                    new String[] {String[].class.getName()});
            return AgentBridgeAccess.locate().present()
                    ? null
                    : "the JVM did not load the agent: "
                            + String.valueOf(answer).trim();
        } catch (IOException | RuntimeException | LinkageError | javax.management.JMException ex) {
            return "the agent could not be loaded: " + ex;
        }
    }
}
