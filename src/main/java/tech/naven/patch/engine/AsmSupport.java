package tech.naven.patch.engine;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Small ASM helpers shared by the patch engine: stack neutral argument marshalling, reference
 * coercion and unboxing of callback return values.
 */
final class AsmSupport {
    /** Internal name of {@code tech.naven.patch.CallbackInfo}. */
    static final String CALLBACK_INFO = "tech/naven/patch/CallbackInfo";
    /** Internal name of {@code tech.naven.patch.CallbackInfoReturnable}. */
    static final String CALLBACK_INFO_RETURNABLE = "tech/naven/patch/CallbackInfoReturnable";
    static final String IS_CANCELLED_DESC = "()Z";
    static final String GET_RETURN_VALUE_DESC = "()Ljava/lang/Object;";

    private AsmSupport() {
    }

    /** Normalises a class name supplied by a patch author ({@code a.b.C} or {@code a/b/C}). */
    static String normalizeOwner(String name) {
        return name == null ? "" : name.trim().replace('.', '/');
    }

    static boolean isStatic(MethodNode method) {
        return (method.access & Opcodes.ACC_STATIC) != 0;
    }

    static boolean isIntLike(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> true;
            default -> false;
        };
    }

    static boolean isPrimitive(Type type) {
        int sort = type.getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY && sort != Type.METHOD;
    }

    /** True when a value of {@code from} may be used where {@code to} is expected without a cast. */
    static boolean sameSort(Type from, Type to) {
        if (isIntLike(from) && isIntLike(to)) {
            return true;
        }
        return from.getSort() == to.getSort();
    }

    /**
     * Pushes the receiver (for non-static methods) followed by every argument of {@code method}.
     * Slot numbering honours long/double taking two slots.
     */
    static void pushSelfAndArgs(InsnList out, MethodNode method) {
        int slot = 0;
        if (!isStatic(method)) {
            out.add(new VarInsnNode(Opcodes.ALOAD, 0));
            slot = 1;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            out.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
            slot += argument.getSize();
        }
    }

    /**
     * Makes the reference currently on the stack assignable to {@code to}.
     *
     * <p>Primitive values must already have a compatible sort: the verifier sees {@code boolean},
     * {@code byte}, {@code char}, {@code short} and {@code int} as the same stack type, so those
     * are accepted as-is.</p>
     */
    static void coerceReference(InsnList out, Type from, Type to) {
        if (from == null || to == null) {
            return;
        }
        if (isPrimitive(from) || isPrimitive(to)) {
            if (!sameSort(from, to)) {
                throw new PatchException("cannot pass a value of type " + from.getClassName()
                        + " where " + to.getClassName() + " is expected");
            }
            return;
        }
        if (!from.equals(to)) {
            out.add(new TypeInsnNode(Opcodes.CHECKCAST, to.getInternalName()));
        }
    }

    /** Boxes the primitive on the stack into its wrapper type (no-op for references). */
    static void box(InsnList out, Type type) {
        if (!isPrimitive(type)) {
            return;
        }
        String owner = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> throw new PatchException("cannot box " + type);
        };
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf",
                "(" + type.getDescriptor() + ")L" + owner + ";", false));
    }

    /** Converts the {@code java.lang.Object} on the stack to {@code type} (unboxing included). */
    static void unbox(InsnList out, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> out.add(new InsnNode(Opcodes.POP));
            case Type.BOOLEAN -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.BYTE -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.CHAR -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
            }
            case Type.SHORT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.FLOAT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.LONG -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case Type.DOUBLE -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
            }
            case Type.OBJECT, Type.ARRAY -> out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            default -> throw new PatchException("unsupported value type " + type);
        }
    }

    /** Type of a parameter slot taken straight from the method descriptor, or {@code null}. */
    static Type parameterType(MethodNode method, String ownerInternalName, int slot) {
        int current = 0;
        if (!isStatic(method)) {
            if (slot == 0) {
                return Type.getObjectType(ownerInternalName);
            }
            current = 1;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (current == slot) {
                return argument;
            }
            current += argument.getSize();
        }
        return null;
    }
}