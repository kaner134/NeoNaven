package tech.naven.patch.engine;

import org.objectweb.asm.ClassWriter;

/**
 * {@link ClassWriter} whose frame computation resolves classes through the class loader that owns
 * the class being transformed.
 *
 * <p>ASM's default {@code getCommonSuperClass} resolves through the class loader of the ASM jar
 * itself, which cannot see Minecraft or mod classes and throws {@code TypeNotPresentException}
 * while computing stack map frames. Falling back to {@code java/lang/Object} for unresolvable
 * types keeps the transformation alive instead of dropping the patch.</p>
 */
final class FrameClassWriter extends ClassWriter {
    private final ClassLoader loader;

    FrameClassWriter(int flags, ClassLoader loader) {
        super(flags);
        this.loader = loader;
    }

    @Override
    protected ClassLoader getClassLoader() {
        return loader != null ? loader : ClassLoader.getSystemClassLoader();
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        Class<?> class1 = load(type1);
        Class<?> class2 = load(type2);
        if (class1 == null || class2 == null) {
            return "java/lang/Object";
        }
        if (class1.isAssignableFrom(class2)) {
            return type1;
        }
        if (class2.isAssignableFrom(class1)) {
            return type2;
        }
        if (class1.isInterface() || class2.isInterface()) {
            return "java/lang/Object";
        }
        Class<?> current = class1;
        do {
            current = current.getSuperclass();
            if (current == null) {
                return "java/lang/Object";
            }
        } while (!current.isAssignableFrom(class2));
        return current.getName().replace('.', '/');
    }

    private Class<?> load(String internalName) {
        try {
            return Class.forName(internalName.replace('/', '.'), false, getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }
}
