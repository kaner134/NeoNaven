package tech.naven.patch.engine;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tech.naven.patch.api.Patch;

/**
 * {@link ClassFileTransformer} that routes every class load / retransformation of a registered
 * patch target through the engine.
 *
 * <p>The transformer is public so tooling (and the offline engine tests) can drive it directly;
 * applications should use {@link PatchEngine#install(java.lang.instrument.Instrumentation)}
 * instead of instantiating it.</p>
 */
public final class PatchTransformer implements ClassFileTransformer {
    private volatile Map<String, List<Class<?>>> byTarget = Map.of();

    public PatchTransformer() {
        rebuild();
    }

    /** Recomputes the target index from the classes currently registered with the engine. */
    public void rebuild() {
        Map<String, List<Class<?>>> index = new HashMap<>();
        for (Class<?> patchClass : PatchEngine.patches()) {
            Patch annotation = patchClass.getAnnotation(Patch.class);
            if (annotation == null) {
                continue;
            }
            String internalName;
            try {
                internalName = annotation.value().getName().replace('.', '/');
            } catch (Throwable ignored) {
                continue;
            }
            index.computeIfAbsent(internalName, key -> new ArrayList<>()).add(patchClass);
        }
        this.byTarget = index;
    }

    /** Internal targets currently known to the transformer. */
    public java.util.Set<String> targets() {
        return byTarget.keySet();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        List<Class<?>> patches = byTarget.get(className);
        if (patches == null || patches.isEmpty()) {
            return null;
        }
        try {
            return PatchApplier.transform(loader, className, classfileBuffer, patches);
        } catch (Throwable t) {
            // Never let a broken patch take the game down.
            PatchEngine.fail("ERROR transform of " + className + " failed", t);
            return null;
        }
    }
}
