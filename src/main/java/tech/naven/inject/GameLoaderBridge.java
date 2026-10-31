package tech.naven.inject;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import tech.naven.patch.engine.PatchEngine;

/**
 * Loaded by a throwaway {@code URLClassLoader} (parent = NeoForge game class loader);
 * re-defines every class of the agent jar onto the game class loader itself.
 *
 * <p>This is required because retransformed Minecraft classes contain
 * {@code INVOKESTATIC} references to our patch handlers (for example
 * {@code tech/naven/patch/impl/MinecraftPatch.tickPre}). When the JVM resolves those
 * references it uses the Minecraft class's defining loader; if our classes lived in a
 * sibling URLClassLoader resolution would fail with {@code NoClassDefFoundError} or
 * {@code VerifyError}.</p>
 */
public final class GameLoaderBridge {
    public static final String RESOURCES_PROP = "naven.resources";

    private GameLoaderBridge() {
    }

    public static void load(String jarPath, ClassLoader gameLoader) throws Throwable {
        LogFile.install();
        System.out.println("[naven] bridge.load jar=" + jarPath + " gameLoader=" + gameLoader);
        long t0 = System.nanoTime();

        Instrumentation resolved = findInstrumentation(gameLoader);
        if (resolved == null) {
            throw new IllegalStateException("agent instrumentation is null - Agent_OnAttach did not run");
        }
        openJavaBaseToSelf(resolved);

        Method defineClass = ClassLoader.class.getDeclaredMethod(
                "defineClass", String.class, byte[].class, int.class, int.class);
        defineClass.setAccessible(true);

        Path resourceDir = Paths.get(System.getProperty("java.io.tmpdir"),
                "naven-resources-" + ProcessHandle.current().pid());
        Files.createDirectories(resourceDir);

        LinkedHashMap<String, byte[]> pending = new LinkedHashMap<>();
        int resources = 0;

        try (ZipFile zip = new ZipFile(jarPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.startsWith("META-INF/")) {
                    continue;
                }
                byte[] bytes;
                try (InputStream is = zip.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }
                if (name.endsWith(".class")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    if (className.equals(GameLoaderBridge.class.getName())) {
                        continue;
                    }
                    pending.put(className, bytes);
                } else {
                    Path target = resourceDir.resolve(name);
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.write(target, bytes);
                    resources++;
                }
            }
        }

        int defined = defineUntilFixedPoint(defineClass, gameLoader, pending);
        if (!pending.isEmpty()) {
            System.err.println("[naven] " + pending.size() + " classes could not be defined on the game loader");
            for (Map.Entry<String, byte[]> e : pending.entrySet()) {
                System.err.println("[naven]   " + e.getKey());
            }
        }

        System.setProperty(RESOURCES_PROP, resourceDir.toAbsolutePath().toString());
        System.out.println("[naven] defined " + defined + " classes, extracted " + resources
                + " resources to " + resourceDir + " (" + ((System.nanoTime() - t0) / 1_000_000L) + " ms)");

        Instrumentation instrumentation = resolved;

        Class<?> bootstrap = Class.forName("tech.naven.inject.NavenBootstrap", true, gameLoader);
        System.out.println("[naven] bootstrap loader = " + bootstrap.getClassLoader()
                + ", minecraft loader = " + gameLoader);
        bootstrap.getMethod("start", String.class, ClassLoader.class, Path.class,
                        Instrumentation.class)
                .invoke(null, jarPath, gameLoader, resourceDir, instrumentation);
    }

    private static int defineUntilFixedPoint(Method defineClass, ClassLoader loader,
                                             LinkedHashMap<String, byte[]> pending) {
        int total = 0;
        boolean progressed = true;
        while (progressed && !pending.isEmpty()) {
            progressed = false;
            Iterator<Map.Entry<String, byte[]>> it = pending.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, byte[]> entry = it.next();
                try {
                    defineClass.invoke(loader, entry.getKey(), entry.getValue(), 0, entry.getValue().length);
                    it.remove();
                    progressed = true;
                    total++;
                } catch (Throwable ignored) {
                    // retried in a later pass once its dependencies exist
                }
            }
        }
        return total;
    }

    /**
     * The stub agent jar is appended to the JVM's system class loader by
     * {@code Agent_OnAttach}. NeoForge replaces the system class loader with its own
     * {@code TransformingClassLoader}, so the exact loader that owns the stub depends on the
     * launch setup - walk every loader that can reach it (game loader chain, system loader,
     * context loader, this bridge's loader) and return the first live Instrumentation.
     */
    private static Instrumentation findInstrumentation(ClassLoader gameLoader) {
        java.util.List<ClassLoader> candidates = new java.util.ArrayList<>();
        for (ClassLoader loader = gameLoader; loader != null; loader = loader.getParent()) {
            candidates.add(loader);
        }
        for (ClassLoader loader = GameLoaderBridge.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (!candidates.contains(loader)) {
                candidates.add(loader);
            }
        }
        ClassLoader system = ClassLoader.getSystemClassLoader();
        if (system != null && !candidates.contains(system)) {
            candidates.add(system);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && !candidates.contains(context)) {
            candidates.add(context);
        }

        for (ClassLoader loader : candidates) {
            try {
                Class<?> stub = Class.forName("tech.navenboot.AgentStub", false, loader);
                Object found = stub.getMethod("getInstrumentation").invoke(null);
                if (found instanceof Instrumentation inst) {
                    System.out.println("[naven] instrumentation resolved from agent stub via " + loader);
                    return inst;
                }
            } catch (Throwable ignored) {
            }
            try {
                Class<?> agent = Class.forName("tech.naven.inject.PatchAgent", false, loader);
                Object found = agent.getMethod("getInstrumentation").invoke(null);
                if (found instanceof Instrumentation inst) {
                    System.out.println("[naven] instrumentation resolved from PatchAgent via " + loader);
                    return inst;
                }
            } catch (Throwable ignored) {
            }
        }
        for (ClassLoader loader : candidates) {
            try {
                java.net.URL url = loader.getResource("tech/navenboot/AgentStub.class");
                System.out.println("[naven] stub resource visible to " + loader + " -> " + url);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void openJavaBaseToSelf(Instrumentation inst) {
        try {
            Module javaBase = ClassLoader.class.getModule();
            Module here = GameLoaderBridge.class.getModule();
            if (javaBase.isOpen("java.lang", here)) {
                return;
            }
            inst.redefineModule(javaBase, Set.of(), Map.of(),
                    Map.of("java.lang", Set.of(here)), Set.of(), Map.of());
        } catch (Throwable t) {
            System.out.println("[naven] redefineModule(java.base/java.lang) failed: " + t);
        }
    }
}