package tech.naven.patch.engine;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Type;
import tech.naven.patch.CallbackInfo;
import tech.naven.patch.CallbackInfoReturnable;
import tech.naven.patch.api.At;
import tech.naven.patch.api.Inject;
import tech.naven.patch.api.ModifyVariable;
import tech.naven.patch.api.Overwrite;
import tech.naven.patch.api.Patch;
import tech.naven.patch.api.Redirect;

/**
 * Immutable, cached description of what a {@code @Patch} class asks the engine to do.
 *
 * <p>The plan is built once (per patch class) from the annotations and the handler signatures;
 * the transformer then only has to match its bindings against the methods of the target class.
 * Invalid handlers are dropped individually and recorded as a failure so the rest of the patch
 * class still works.</p>
 */
final class PatchPlan {
    enum Kind {
        OVERWRITE,
        INJECT,
        REDIRECT,
        MODIFY_VARIABLE
    }

    /** One annotated handler, already validated and decoded. */
    static final class Binding {
        Kind kind;
        Method handler;
        Class<?>[] params;
        String handlerOwner;
        String handlerName;
        String handlerDesc;
        Type handlerReturn;
        String targetName;
        String explicitDesc;
        At at;
        boolean cancellable;
        boolean ciReturnable;
        Type ciValueType;
        Type ciPrimitiveType;
        String invokeOwner;
        String invokeDesc;
        int ordinal;
        int varIndex;
        int varOrdinal;
        Type valueType;
        String id;

        /**
         * True when a target returning {@code target} can be fed to this callback.
         *
         * <p>{@code CallbackInfoReturnable<Integer>} matches both {@code int} and
         * {@code Integer}: generics cannot hold primitives, so the boxed type is what a handler
         * has to declare for a primitive target.</p>
         */
        boolean acceptsReturn(Type target) {
            if (!ciReturnable) {
                return true;
            }
            return ciValueType.equals(target)
                    || (ciPrimitiveType != null && ciPrimitiveType.equals(target));
        }
    }

    private static final Map<Class<?>, PatchPlan> CACHE = new ConcurrentHashMap<>();

    final Class<?> patchClass;
    final List<Binding> bindings = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    String targetInternalName;
    String error;

    static PatchPlan of(Class<?> patchClass) {
        PatchPlan plan = CACHE.get(patchClass);
        if (plan != null) {
            return plan;
        }
        plan = new PatchPlan(patchClass);
        PatchPlan existing = CACHE.putIfAbsent(patchClass, plan);
        return existing != null ? existing : plan;
    }

    private PatchPlan(Class<?> patchClass) {
        this.patchClass = patchClass;
        Patch patch;
        try {
            patch = patchClass.getAnnotation(Patch.class);
        } catch (Throwable t) {
            this.error = patchClass.getName() + ": cannot read @Patch (" + t + ")";
            return;
        }
        if (patch == null) {
            this.error = patchClass.getName() + " is not annotated with @Patch";
            return;
        }
        try {
            this.targetInternalName = patch.value().getName().replace('.', '/');
        } catch (Throwable t) {
            this.error = patchClass.getName() + ": cannot resolve @Patch target (" + t + ")";
            return;
        }
        if (!Modifier.isPublic(patchClass.getModifiers())) {
            this.error = patchClass.getName() + " must be public: its handlers are invoked from "
                    + targetInternalName.replace('/', '.') + " and the JVM checks class access";
            return;
        }
        try {
            for (Method method : patchClass.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (!isHandler(method)) {
                    continue;
                }
                try {
                    Binding binding = parse(method);
                    if (binding != null) {
                        bindings.add(binding);
                    }
                } catch (Throwable t) {
                    errors.add("ERROR " + patchClass.getName() + "#" + method.getName() + ": " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            errors.add("ERROR " + patchClass.getName() + ": " + t);
        }
        // @Overwrite replaces the whole body, so it has to run before anything injected into it.
        bindings.sort(Comparator.comparingInt(binding -> binding.kind == Kind.OVERWRITE ? 0 : 1));
    }

    private static boolean isHandler(Method method) {
        return method.isAnnotationPresent(Inject.class)
                || method.isAnnotationPresent(Redirect.class)
                || method.isAnnotationPresent(ModifyVariable.class)
                || method.isAnnotationPresent(Overwrite.class);
    }

    private Binding parse(Method method) {
        Inject inject = method.getAnnotation(Inject.class);
        Redirect redirect = method.getAnnotation(Redirect.class);
        ModifyVariable modifyVariable = method.getAnnotation(ModifyVariable.class);
        Overwrite overwrite = method.getAnnotation(Overwrite.class);
        int annotations = (inject != null ? 1 : 0) + (redirect != null ? 1 : 0)
                + (modifyVariable != null ? 1 : 0) + (overwrite != null ? 1 : 0);
        if (annotations != 1) {
            throw new PatchException("a handler must carry exactly one patch annotation");
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new PatchException("handler must be static");
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            throw new PatchException("handler must be public: it is invoked from the target class");
        }

        Binding binding = new Binding();
        binding.handler = method;
        binding.params = method.getParameterTypes();
        binding.handlerOwner = Type.getInternalName(patchClass);
        binding.handlerName = method.getName();
        binding.handlerDesc = Type.getMethodDescriptor(method);
        binding.handlerReturn = Type.getReturnType(binding.handlerDesc);
        binding.id = patchClass.getName() + "#" + method.getName();

        if (inject != null) {
            binding.kind = Kind.INJECT;
            binding.targetName = inject.method();
            binding.explicitDesc = emptyToNull(inject.desc());
            binding.at = inject.at();
            binding.cancellable = inject.cancellable();
            if (binding.handlerReturn != Type.VOID_TYPE) {
                throw new PatchException("@Inject handler must return void");
            }
            if (binding.params.length == 0) {
                throw new PatchException("@Inject handler must take CallbackInfo/CallbackInfoReturnable as last parameter");
            }
            Class<?> callback = binding.params[binding.params.length - 1];
            if (callback != CallbackInfo.class && callback != CallbackInfoReturnable.class) {
                throw new PatchException("@Inject handler must take CallbackInfo (void target)"
                        + " or CallbackInfoReturnable<T> (non-void target) as last parameter, found "
                        + callback.getName());
            }
            // The two callback classes are deliberately unrelated, so the *declared* type decides.
            binding.ciReturnable = callback == CallbackInfoReturnable.class;
            if (binding.ciReturnable) {
                binding.ciValueType = callbackValueType(method);
                if (binding.ciValueType == null) {
                    throw new PatchException("cannot infer the target return type: declare a concrete"
                            + " CallbackInfoReturnable<R> (not a raw or type-variable one)");
                }
                binding.ciPrimitiveType = primitiveOf(binding.ciValueType);
            }
            if (binding.at.value() == At.Type.INVOKE && binding.at.desc().isEmpty()) {
                throw new PatchException("@At(INVOKE) requires desc()");
            }
            checkDescReturnType(binding);
        } else if (redirect != null) {
            binding.kind = Kind.REDIRECT;
            binding.targetName = redirect.method();
            binding.explicitDesc = emptyToNull(redirect.desc());
            binding.invokeOwner = AsmSupport.normalizeOwner(redirect.target());
            binding.invokeDesc = redirect.targetDesc().trim();
            binding.ordinal = redirect.ordinal();
            if (binding.invokeDesc.isEmpty()) {
                throw new PatchException("@Redirect requires targetDesc()");
            }
            if (binding.ordinal < 0) {
                throw new PatchException("@Redirect ordinal() must be >= 0");
            }
            Type invoked = Type.getReturnType(binding.invokeDesc);
            if (!binding.handlerReturn.equals(invoked)) {
                throw new PatchException("handler returns " + binding.handlerReturn.getClassName()
                        + " but the redirected invocation returns " + invoked.getClassName());
            }
        } else if (modifyVariable != null) {
            binding.kind = Kind.MODIFY_VARIABLE;
            binding.targetName = modifyVariable.method();
            binding.explicitDesc = emptyToNull(modifyVariable.desc());
            binding.at = modifyVariable.at();
            binding.varIndex = modifyVariable.index();
            binding.varOrdinal = modifyVariable.ordinal();
            if (binding.at.value() != At.Type.HEAD) {
                throw new PatchException("only At.Type.HEAD is supported for @ModifyVariable");
            }
            if (binding.varIndex < -1) {
                throw new PatchException("@ModifyVariable index() must be -1 or a valid slot");
            }
            if (binding.varOrdinal < 0) {
                throw new PatchException("@ModifyVariable ordinal() must be >= 0");
            }
            if (binding.params.length == 0) {
                throw new PatchException("@ModifyVariable handler must take the value as last parameter");
            }
            binding.valueType = Type.getType(binding.params[binding.params.length - 1]);
            if (binding.valueType == Type.VOID_TYPE) {
                throw new PatchException("@ModifyVariable handler cannot take void");
            }
            if (!binding.handlerReturn.equals(binding.valueType)) {
                throw new PatchException("handler must return " + binding.valueType.getClassName()
                        + " (the type of its last parameter)");
            }
        } else {
            binding.kind = Kind.OVERWRITE;
            binding.targetName = overwrite.method();
            binding.explicitDesc = emptyToNull(overwrite.desc());
        }

        if (binding.targetName == null || binding.targetName.trim().isEmpty()) {
            throw new PatchException("method() must name the target method");
        }
        binding.targetName = binding.targetName.trim();
        return binding;
    }

    /** Validates the return type of an explicit descriptor against the handler shape. */
    private void checkDescReturnType(Binding binding) {
        if (binding.explicitDesc == null) {
            return;
        }
        Type actual = Type.getReturnType(binding.explicitDesc);
        if (binding.kind == Kind.INJECT) {
            if (binding.ciReturnable) {
                if (!binding.acceptsReturn(actual)) {
                    throw new PatchException("handler declares CallbackInfoReturnable<"
                            + binding.ciValueType.getClassName() + "> but desc() returns "
                            + actual.getClassName());
                }
            } else if (binding.cancellable && actual != Type.VOID_TYPE) {
                throw new PatchException("a cancellable @Inject on a non-void method must take"
                        + " CallbackInfoReturnable<R>");
            }
        } else if (binding.kind == Kind.OVERWRITE) {
            if (!binding.handlerReturn.equals(actual)) {
                throw new PatchException("handler returns " + binding.handlerReturn.getClassName()
                        + " but desc() declares " + actual.getClassName());
            }
        }
    }

    /** Maps a boxed callback type to its primitive counterpart, or {@code null}. */
    private static Type primitiveOf(Type type) {
        if (type == null || type.getSort() != Type.OBJECT) {
            return null;
        }
        return switch (type.getInternalName()) {
            case "java/lang/Boolean" -> Type.BOOLEAN_TYPE;
            case "java/lang/Byte" -> Type.BYTE_TYPE;
            case "java/lang/Character" -> Type.CHAR_TYPE;
            case "java/lang/Short" -> Type.SHORT_TYPE;
            case "java/lang/Integer" -> Type.INT_TYPE;
            case "java/lang/Long" -> Type.LONG_TYPE;
            case "java/lang/Float" -> Type.FLOAT_TYPE;
            case "java/lang/Double" -> Type.DOUBLE_TYPE;
            default -> null;
        };
    }

    /** Extracts {@code R} from {@code CallbackInfoReturnable<R>}. */
    private static Type callbackValueType(Method method) {
        java.lang.reflect.Type[] generic = method.getGenericParameterTypes();
        if (generic.length == 0) {
            return null;
        }
        java.lang.reflect.Type last = generic[generic.length - 1];
        if (!(last instanceof ParameterizedType parameterized)) {
            return null;
        }
        java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1) {
            return null;
        }
        java.lang.reflect.Type argument = arguments[0];
        if (argument instanceof Class<?> concrete) {
            return concrete == void.class ? null : Type.getType(concrete);
        }
        if (argument instanceof ParameterizedType nested && nested.getRawType() instanceof Class<?> raw) {
            return Type.getType(raw);
        }
        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    String errorText() {
        return error;
    }
}
