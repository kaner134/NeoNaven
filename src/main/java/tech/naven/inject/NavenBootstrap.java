package tech.naven.inject;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import tech.naven.Naven;
import tech.naven.patch.Patches;
import tech.naven.patch.Reflect;
import tech.naven.patch.engine.PatchEngine;

/**
 * Final hand-off of the injection path, running on the game loader.
 *
 * <p>Every class of the agent jar has already been defined on the NeoForge game class
 * loader, so patch handlers, {@link Naven} and Minecraft share a defining loader.</p>
 *
 * <p>The mod path does three things at load time that a hot injection must reproduce
 * explicitly: apply the mixins (here: retransform already loaded targets), register the
 * render pipelines / enable the stencil (normally mod-bus events that already fired), and
 * construct the client ({@code Naven.modRegister()}, normally called from the
 * {@code Minecraft.<init>} mixin).</p>
 */
public final class NavenBootstrap {
    private static volatile boolean started;

    private NavenBootstrap() {
    }

    public static synchronized void start(String jarPath, ClassLoader gameLoader, Path resourceDir,
                                          Instrumentation instrumentation) {
        if (started) {
            System.out.println("[naven] bootstrap already ran; ignoring");
            return;
        }
        started = true;

        LogFile.install();
        System.out.println("[naven] bootstrapping from " + jarPath);
        System.out.println("[naven]   Minecraft loader = " + Minecraft.class.getClassLoader());
        System.out.println("[naven]   Naven loader     = " + Naven.class.getClassLoader());
        System.out.println("[naven]   instrumentation  = " + instrumentation);

        Runnable init = () -> {
            try {
                initOnRenderThread(jarPath, gameLoader, resourceDir, instrumentation);
            } catch (Throwable t) {
                System.err.println("[naven] bootstrap failed");
                t.printStackTrace(System.err);
            }
        };

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            System.out.println("[naven] Minecraft not constructed yet; running inline");
            init.run();
            return;
        }

        // Touch GL / registries / fonts from the render thread, exactly like the mod path
        // does during Minecraft's constructor.
        CompletableFuture<Void> done = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                init.run();
            } finally {
                done.complete(null);
            }
        });
        try {
            done.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.out.println("[naven] render-thread bootstrap did not complete within 20s: " + e);
        }
    }

    private static void initOnRenderThread(String jarPath, ClassLoader gameLoader, Path resourceDir,
                                           Instrumentation instrumentation) throws java.io.IOException {
        long t0 = System.nanoTime();

        Reflect.setInstrumentation(instrumentation);
        Resources.init(Path.of(jarPath), resourceDir);
        Resources.registerPack();

        // The client has to exist before the patches go live: the game is already
        // running, so a retransformed tick/network method can fire on another thread the
        // instant retransformClasses returns. The mod path is immune because its
        // Minecraft.<init> hook constructs the client before any of that can happen.
        PipelineBootstrap.register();
        StencilSupport.enable();
        Naven.modRegister();

        PatchEngine.install(instrumentation);
        PatchEngine.register(Patches.all());
        PatchEngine.apply();
        List<String> failures = PatchEngine.failures();
        if (!failures.isEmpty()) {
            System.err.println("[naven] " + failures.size() + " patch(es) failed to apply:");
            for (String failure : failures) {
                System.err.println("[naven]   " + failure);
            }
        }

        System.out.println("[naven] injection bootstrap finished in "
                + ((System.nanoTime() - t0) / 1_000_000L) + " ms");
    }
}