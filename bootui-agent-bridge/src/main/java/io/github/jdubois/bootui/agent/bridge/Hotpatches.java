package io.github.jdubois.bootui.agent.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hotpatch panel's bridge entry points (mani fork): redefines application classes with new bytecode and restores
 * them, for the armed claim {@code token} only. Classes outside the claim's application packages, and classes the agent
 * never instruments, are refused here, before the agent is asked. Never throws: every answer is a map whose
 * {@code status} is {@code ok}, {@value #INVALID}, {@code stale}, {@code unavailable}, or {@code failed}, with a
 * {@code reason} unless {@code ok}.
 */
public final class Hotpatches {

    /** The bridge entry points' version, so an engine can tell this bridge from one without the panel. */
    public static final int PROTOCOL = 1;

    public static final String INVALID = "invalid";

    private Hotpatches() {}

    /** Redefines {@code classes} (binary name to bytecode) of the current run. */
    public static Map<String, Object> redefine(long token, Map<String, byte[]> classes) {
        try {
            Claim claim = AgentBridge.current();
            String refusal = refusal(claim, token, classes == null ? null : classes.keySet());
            if (refusal != null) {
                return answer(
                        claim == null || claim.token != token || !claim.armed ? AgentBridge.STALE : INVALID, refusal);
            }
            Map<String, Object> call = new LinkedHashMap<String, Object>();
            call.put("op", "hotpatch");
            call.put("generation", Long.valueOf(claim.generation));
            call.put("action", "redefine");
            call.put("classes", new LinkedHashMap<String, byte[]>(classes));
            return agent(call);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return answer(AgentBridge.FAILED, "the bridge failed to redefine: " + ex);
        }
    }

    /** Restores {@code classNames} of the current run to the bytecode they were loaded from. */
    public static Map<String, Object> restore(long token, List<String> classNames) {
        try {
            Claim claim = AgentBridge.current();
            String refusal = refusal(claim, token, classNames);
            if (refusal != null) {
                return answer(
                        claim == null || claim.token != token || !claim.armed ? AgentBridge.STALE : INVALID, refusal);
            }
            Map<String, Object> call = new LinkedHashMap<String, Object>();
            call.put("op", "hotpatch");
            call.put("generation", Long.valueOf(claim.generation));
            call.put("action", "restore");
            call.put("classNames", new ArrayList<String>(classNames));
            return agent(call);
        } catch (Throwable ex) {
            AgentBridge.error(ex);
            return answer(AgentBridge.FAILED, "the bridge failed to restore: " + ex);
        }
    }

    private static String refusal(Claim claim, long token, Collection<String> names) {
        if (claim == null || claim.token != token || !claim.armed) {
            return "this claim was replaced or ended";
        }
        if (!AgentBridge.attached()) {
            return "the BootUI agent did not start";
        }
        if (names == null || names.isEmpty()) {
            return "no class given";
        }
        for (String name : names) {
            if (!inPackages(name, claim.packages)) {
                return name + " is not in the application's packages " + claim.packages;
            }
            if (Exclusions.excluded(name)) {
                return "the BootUI agent never changes " + name;
            }
        }
        return null;
    }

    private static Map<String, Object> agent(Map<String, Object> call) {
        Map<String, Object> answer = AgentBridge.callAgent(call);
        if (answer == null) {
            return answer(AgentBridge.FAILED, "the BootUI agent did not answer");
        }
        if ("failed".equals(answer.get("status"))
                && String.valueOf(answer.get("reason")).startsWith("unknown operation")) {
            return answer(AgentBridge.UNAVAILABLE, "this BootUI agent predates the Hotpatch panel");
        }
        return new LinkedHashMap<String, Object>(answer);
    }

    private static boolean inPackages(String className, List<String> packages) {
        for (String prefix : packages) {
            if (className.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> answer(String status, String reason) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("status", status);
        map.put("reason", reason);
        return map;
    }
}
