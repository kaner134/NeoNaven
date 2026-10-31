package tech.naven.patch;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflective access to private members of Minecraft classes.
 *
 * <p>Mixin's {@code @Shadow} merges handler code into the target class, which is impossible for a
 * class that has already been loaded (the JVM forbids adding members on redefinition). Hot
 * injection therefore reads and writes shadowed members reflectively.</p>
 */
public final class Reflect {
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static volatile Instrumentation instrumentation;

    private Reflect() {
    }

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    /** Opens the target's package to the unnamed module so private members become reachable. */
    public static void open(Class<?> owner) {
        Instrumentation inst = instrumentation;
        if (inst == null || owner == null) {
            return;
        }
        Module module = owner.getModule();
        if (module == null || module.isOpen(owner.getPackageName(), Reflect.class.getModule())) {
            return;
        }
        try {
            inst.redefineModule(module, Set.of(), Map.of(),
                    Map.of(owner.getPackageName(), Set.of(Reflect.class.getModule())),
                    Set.of(), Map.of());
        } catch (Throwable ignored) {
            // Already open or not redefinable; setAccessible will decide.
        }
    }

    private static Field field(Class<?> owner, String name) {
        String key = owner.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> current = owner;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                FIELDS.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("No field " + name + " on " + owner.getName());
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(Object instance, Class<?> owner, String name) {
        try {
            return (T) field(owner, name).get(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int getInt(Object instance, Class<?> owner, String name) {
        try {
            return field(owner, name).getInt(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static float getFloat(Object instance, Class<?> owner, String name) {
        try {
            return field(owner, name).getFloat(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long getLong(Object instance, Class<?> owner, String name) {
        try {
            return field(owner, name).getLong(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean getBoolean(Object instance, Class<?> owner, String name) {
        try {
            return field(owner, name).getBoolean(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static double getDouble(Object instance, Class<?> owner, String name) {
        try {
            return field(owner, name).getDouble(instance);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void set(Object instance, Class<?> owner, String name, Object value) {
        try {
            field(owner, name).set(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setInt(Object instance, Class<?> owner, String name, int value) {
        try {
            field(owner, name).setInt(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setFloat(Object instance, Class<?> owner, String name, float value) {
        try {
            field(owner, name).setFloat(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setLong(Object instance, Class<?> owner, String name, long value) {
        try {
            field(owner, name).setLong(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setBoolean(Object instance, Class<?> owner, String name, boolean value) {
        try {
            field(owner, name).setBoolean(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void setDouble(Object instance, Class<?> owner, String name, double value) {
        try {
            field(owner, name).setDouble(instance, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>[] types) {
        String key = owner.getName() + '#' + name + java.util.Arrays.toString(types);
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        Class<?> current = owner;
        while (current != null) {
            try {
                Method m = current.getDeclaredMethod(name, types);
                m.setAccessible(true);
                METHODS.put(key, m);
                return m;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalStateException("No method " + name + java.util.Arrays.toString(types)
                + " on " + owner.getName());
    }

    public static Object call(Object instance, Class<?> owner, String name, Class<?>[] types, Object... args) {
        try {
            return method(owner, name, types).invoke(instance, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Reflection must stay transparent: several patched methods use exceptions for
            // control flow (e.g. Connection.genericsFtw throws RunningOnDifferentThreadException
            // which channelRead0 catches to reschedule the packet). Wrapping that in
            // IllegalStateException changes game behaviour, so rethrow the real cause.
            throw Reflect.<RuntimeException>sneakyThrow(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    public static boolean isStatic(Class<?> owner, String name) {
        return Modifier.isStatic(field(owner, name).getModifiers());
    }
}