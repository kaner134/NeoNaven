package tech.navenboot;

import java.lang.instrument.Instrumentation;

/**
 * Minimal java-agent entry point, shipped in its own tiny jar.
 *
 * <p>Why a separate jar: {@code Agent_OnAttach} appends the agent jar to the
 * <em>system</em> (application) class loader. If the full client jar were passed there,
 * every {@code tech.naven.*} class would be resolved by the app loader first (parent-first
 * delegation), and those copies cannot see {@code net.minecraft.client.Minecraft}. Keeping
 * the agent jar minimal means the game class loader is the only loader that ever defines
 * the client classes.</p>
 *
 * <p>The DLL passes the real client jar as the agent option:
 * {@code Agent_OnAttach(stubJarPath + "=" + clientJarPath)}.</p>
 */
public final class AgentStub {
    private static volatile Instrumentation instrumentation;
    private static volatile String clientJarPath;

    private AgentStub() {
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if (inst == null) {
            System.err.println("[naven] AgentStub.agentmain without Instrumentation");
            return;
        }
        instrumentation = inst;
        clientJarPath = args;
        System.out.println("[naven] agent stub attached; retransform="
                + inst.isRetransformClassesSupported() + " clientJar=" + args);
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static String getClientJarPath() {
        return clientJarPath;
    }
}
