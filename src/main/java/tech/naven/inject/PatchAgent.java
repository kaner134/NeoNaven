package tech.naven.inject;

import java.lang.instrument.Instrumentation;

/**
 * Java-agent entry point used by the DLL injection path.
 *
 * <p>The native DLL calls {@code Agent_OnAttach} from the JDK's {@code instrument.dll},
 * which invokes {@link #agentmain(String, Instrumentation)} for the {@code Agent-Class}
 * declared in the jar manifest. The instance is only recorded here; the patch engine is
 * installed later by {@link NavenBootstrap} so that install/register/apply all run in the
 * same class-loader copy of the engine.</p>
 */
public final class PatchAgent {
    private static volatile Instrumentation instrumentation;

    private PatchAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if (inst == null) {
            System.err.println("[naven] agentmain called without Instrumentation");
            return;
        }
        instrumentation = inst;
        System.out.println("[naven] java agent attached; retransform=" + inst.isRetransformClassesSupported());
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
