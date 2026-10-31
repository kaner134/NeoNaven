package tech.naven.patch.engine;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import tech.naven.patch.Reflect;
import tech.naven.patch.api.Patch;

/**
 * Entry point of the runtime bytecode patch engine.
 *
 * <p>The client is injected into an already running JVM, where Mixin's merge-based transformation
 * is impossible: the JVM forbids redefinition that adds or removes fields and methods. The engine
 * therefore only ever rewrites <em>method bodies</em> of already loaded classes, using
 * {@link Instrumentation#retransformClasses(Class[])}.</p>
 *
 * <p>Typical use:</p>
 * <pre>{@code
 * PatchEngine.install(instrumentation);        // register the ClassFileTransformer
 * PatchEngine.register(GameRendererPatch.class, LocalPlayerPatch.class);
 * PatchEngine.apply();                         // retransform every target class
 * for (String failure : PatchEngine.failures()) {
 *     LOGGER.warn(failure);
 * }
 * }</pre>
 *
 * <p>All methods are safe to call from any thread; transformation itself may run concurrently for
 * different classes.</p>
 */
public final class PatchEngine {
    private static final List<Class<?>> PATCHES = new CopyOnWriteArrayList<>();
    private static final Set<String> FAILURES =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private static volatile Instrumentation instrumentation;
    private static volatile PatchTransformer transformer;
    private static volatile Instrumentation transformerInstalledOn;

    private PatchEngine() {
    }

    /**
     * Installs the class file transformer.
     *
     * <p>Must be called before {@link #apply()}. Calling it again with the same
     * {@link Instrumentation} is a no-op; a different instrumentation instance gets the same
     * transformer attached to it.</p>
     */
    public static synchronized void install(Instrumentation inst) {
        if (inst == null) {
            throw new IllegalArgumentException("instrumentation must not be null");
        }
        instrumentation = inst;
        try {
            Reflect.setInstrumentation(inst);
        } catch (Throwable ignored) {
            // Reflect is optional for the engine itself.
        }
        if (transformer == null) {
            PatchTransformer created = new PatchTransformer();
            inst.addTransformer(created, true);
            transformer = created;
            transformerInstalledOn = inst;
        } else if (transformerInstalledOn != inst) {
            inst.addTransformer(transformer, true);
            transformerInstalledOn = inst;
        }
    }

    /**
     * Registers {@code @Patch} classes. Handlers are parsed eagerly so bad signatures show up in
     * {@link #failures()} before any class is touched.
     */
    public static synchronized void register(Class<?>... patchClasses) {
        if (patchClasses == null) {
            return;
        }
        for (Class<?> patchClass : patchClasses) {
            if (patchClass == null) {
                fail("ERROR register(null)");
                continue;
            }
            if (!patchClass.isAnnotationPresent(Patch.class)) {
                fail("ERROR " + patchClass.getName() + " is not annotated with @Patch");
                continue;
            }
            if (PATCHES.contains(patchClass)) {
                continue;
            }
            PATCHES.add(patchClass);
            try {
                PatchPlan plan = PatchPlan.of(patchClass);
                if (plan.errorText() != null) {
                    fail("ERROR " + plan.errorText());
                }
                for (String error : plan.errors) {
                    fail(error);
                }
            } catch (Throwable t) {
                fail("ERROR cannot parse patch " + patchClass.getName(), t);
            }
        }
        PatchTransformer current = transformer;
        if (current != null) {
            current.rebuild();
        }
    }

    /**
     * Retransforms every patch target so the registered patches take effect.
     *
     * <p>Also picks up patches for classes that are not loaded yet: the transformer applies them
     * at class load time. Clears {@link #failures()} first, then repopulates it with plan errors
     * and with everything that did not match.</p>
     */
    public static synchronized void apply() {
        FAILURES.clear();
        for (Class<?> patchClass : PATCHES) {
            try {
                PatchPlan plan = PatchPlan.of(patchClass);
                if (plan.errorText() != null) {
                    fail("ERROR " + plan.errorText());
                }
                for (String error : plan.errors) {
                    fail(error);
                }
            } catch (Throwable t) {
                fail("ERROR cannot parse patch " + patchClass.getName(), t);
            }
        }

        Instrumentation inst = instrumentation;
        if (inst == null) {
            fail("ERROR PatchEngine.install(Instrumentation) has not been called");
            return;
        }
        PatchTransformer current = transformer;
        if (current != null) {
            current.rebuild();
        }

        Map<Class<?>, List<Class<?>>> targets = new LinkedHashMap<>();
        for (Class<?> patchClass : PATCHES) {
            Patch annotation = patchClass.getAnnotation(Patch.class);
            if (annotation == null) {
                continue;
            }
            Class<?> target;
            try {
                target = annotation.value();
            } catch (Throwable t) {
                fail("ERROR " + patchClass.getName() + ": cannot resolve target class", t);
                continue;
            }
            targets.computeIfAbsent(target, key -> new ArrayList<>()).add(patchClass);
        }

        for (Map.Entry<Class<?>, List<Class<?>>> entry : targets.entrySet()) {
            Class<?> target = entry.getKey();
            if (!inst.isModifiableClass(target)) {
                fail("ERROR target " + target.getName() + " is not modifiable by the JVM");
                continue;
            }
            try {
                // One class at a time, so a VerifyError can be attributed to a single target.
                inst.retransformClasses(target);
            } catch (Throwable t) {
                fail("ERROR retransformation of " + target.getName() + " failed", t);
            }
        }
    }

    /**
     * @return an immutable snapshot of everything that did not apply during the last
     *         {@link #apply()}: unbound methods, missing injection points, invalid handler
     *         signatures and transformation errors. Empty means every registered patch applied.
     */
    public static List<String> failures() {
        synchronized (FAILURES) {
            return Collections.unmodifiableList(new ArrayList<>(FAILURES));
        }
    }

    /** Registered patch classes, in registration order. */
    static List<Class<?>> patches() {
        return new ArrayList<>(PATCHES);
    }

    /** Records a failure in memory and on {@code System.err}. */
    static void fail(String message) {
        if (FAILURES.add(message)) {
            System.err.println("[NavenPatch] " + message);
        }
    }

    /** Records a failure with its stack trace in memory and on {@code System.err}. */
    static void fail(String message, Throwable cause) {
        if (FAILURES.add(message)) {
            System.err.println("[NavenPatch] " + message);
            if (cause != null) {
                cause.printStackTrace(System.err);
            }
        }
    }
}
