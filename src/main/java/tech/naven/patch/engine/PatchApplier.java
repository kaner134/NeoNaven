package tech.naven.patch.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import tech.naven.patch.api.At;

/**
 * Applies a {@link PatchPlan} to one {@link ClassNode}.
 *
 * <p>Every handler is applied in isolation: a handler that cannot be bound or whose bytecode
 * cannot be crafted is reported as a failure and skipped, the remaining handlers (and the rest of
 * the class) are still transformed. If a patch class blows up half way through, the class node is
 * rebuilt from the original bytes and only the already successful patch classes are replayed, so
 * no partially patched method ever reaches the JVM.</p>
 */
final class PatchApplier {
    private final ClassNode classNode;
    private final String className;
    private final Set<MethodNode> overwritten =
            Collections.newSetFromMap(new IdentityHashMap<MethodNode, Boolean>());
    private int applied;

    private PatchApplier(ClassNode classNode, String className) {
        this.classNode = classNode;
        this.className = className;
    }

    /**
     * Transforms {@code bytes} with every patch registered for this class.
     *
     * @return the transformed class file, or {@code null} when nothing was patched or the
     *         transformation failed (the JVM then keeps the original bytes)
     */
    static byte[] transform(ClassLoader loader, String className, byte[] bytes, List<Class<?>> patches) {
        ClassNode node;
        try {
            node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
        } catch (Throwable t) {
            PatchEngine.fail("ERROR cannot parse " + className, t);
            return null;
        }

        int applied = 0;
        List<Class<?>> appliedPatches = new ArrayList<>();
        for (Class<?> patchClass : patches) {
            PatchPlan plan;
            try {
                plan = PatchPlan.of(patchClass);
            } catch (Throwable t) {
                PatchEngine.fail("ERROR cannot read patch " + patchClass.getName(), t);
                continue;
            }
            if (plan.errorText() != null) {
                PatchEngine.fail("ERROR " + plan.errorText());
                continue;
            }
            try {
                applied += new PatchApplier(node, className).applyPlan(plan);
                appliedPatches.add(patchClass);
            } catch (Throwable t) {
                PatchEngine.fail("ERROR patch " + patchClass.getName() + " failed on " + className, t);
                node = rebuild(bytes, className, appliedPatches);
            }
        }
        if (applied == 0) {
            return null;
        }
        try {
            ClassWriter writer = new FrameClassWriter(
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable t) {
            PatchEngine.fail("ERROR cannot write patched class " + className, t);
            return null;
        }
    }

    private static ClassNode rebuild(byte[] bytes, String className, List<Class<?>> appliedPatches) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        for (Class<?> patchClass : appliedPatches) {
            try {
                new PatchApplier(node, className).applyPlan(PatchPlan.of(patchClass));
            } catch (Throwable ignored) {
                // Already reported when it originally failed.
            }
        }
        return node;
    }

    /** @return the number of handlers that actually modified the class */
    private int applyPlan(PatchPlan plan) {
        Map<PatchPlan.Binding, List<MethodNode>> matches = new LinkedHashMap<>();
        for (PatchPlan.Binding binding : plan.bindings) {
            matches.put(binding, new ArrayList<>());
        }
        for (MethodNode method : classNode.methods) {
            for (PatchPlan.Binding binding : plan.bindings) {
                if (!binding.targetName.equals(method.name)) {
                    continue;
                }
                boolean matched;
                try {
                    matched = signatureMatches(binding, method);
                } catch (Throwable t) {
                    matched = false;
                }
                if (matched) {
                    matches.get(binding).add(method);
                }
            }
        }

        for (PatchPlan.Binding binding : plan.bindings) {
            List<MethodNode> candidates = matches.get(binding);
            if (candidates.isEmpty()) {
                PatchEngine.fail("MISS  " + binding.id + " -> " + className + ": no target method "
                        + binding.targetName + expectedShape(binding) + overloadHint(binding));
                continue;
            }
            if (candidates.size() > 1) {
                // Covariant overrides leave a synthetic bridge next to the real method; when the
                // handler could match either, the non-bridge one is the method patch authors mean.
                List<MethodNode> concrete = new ArrayList<>();
                for (MethodNode candidate : candidates) {
                    if ((candidate.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) == 0) {
                        concrete.add(candidate);
                    }
                }
                if (concrete.size() == 1) {
                    candidates = concrete;
                }
            }
            if (candidates.size() > 1) {
                PatchEngine.fail("MISS  " + binding.id + " -> " + className + ": ambiguous overloads "
                        + methodNames(candidates) + ", add desc() to disambiguate");
                continue;
            }
            MethodNode method = candidates.get(0);
            try {
                applyOne(binding, method);
                applied++;
            } catch (Throwable t) {
                String detail = t.getMessage() == null ? t.toString() : t.getMessage();
                PatchEngine.fail("MISS  " + binding.id + " -> " + className + "." + method.name
                        + method.desc + ": " + detail);
                if (!(t instanceof PatchException)) {
                    t.printStackTrace(System.err);
                }
            }
        }
        return applied;
    }

    private void applyOne(PatchPlan.Binding binding, MethodNode method) {
        switch (binding.kind) {
            case INJECT -> applyInject(binding, method);
            case REDIRECT -> applyRedirect(binding, method);
            case MODIFY_VARIABLE -> applyModifyVariable(binding, method);
            case OVERWRITE -> applyOverwrite(binding, method);
        }
    }

    // ------------------------------------------------------------------
    // binding
    // ------------------------------------------------------------------

    private boolean signatureMatches(PatchPlan.Binding binding, MethodNode method) {
        Type[] args = Type.getArgumentTypes(method.desc);
        Type returnType = Type.getReturnType(method.desc);
        int self = AsmSupport.isStatic(method) ? 0 : 1;
        Class<?>[] params = binding.params;

        if (binding.explicitDesc != null) {
            switch (binding.kind) {
                case INJECT: {
                    if (params.length != self + args.length + 1) {
                        return false;
                    }
                    return !binding.ciReturnable || binding.acceptsReturn(returnType);
                }
                case OVERWRITE:
                    return params.length == self + args.length && binding.handlerReturn.equals(returnType);
                case MODIFY_VARIABLE:
                    return params.length == self + args.length + 1;
                case REDIRECT:
                    return binding.explicitDesc.equals(method.desc);
                default:
                    return false;
            }
        }

        switch (binding.kind) {
            case INJECT: {
                if (params.length - self - 1 != args.length) {
                    return false;
                }
                if (binding.ciReturnable) {
                    if (!binding.acceptsReturn(returnType)) {
                        return false;
                    }
                } else if (!returnType.equals(Type.VOID_TYPE)) {
                    // Without a type argument there is nothing to infer a non-void return from.
                    return false;
                }
                return argumentTypesMatch(params, self, args);
            }
            case OVERWRITE: {
                if (params.length - self != args.length) {
                    return false;
                }
                if (!binding.handlerReturn.equals(returnType)) {
                    return false;
                }
                return argumentTypesMatch(params, self, args);
            }
            case MODIFY_VARIABLE: {
                if (params.length - self - 1 != args.length) {
                    return false;
                }
                return argumentTypesMatch(params, self, args);
            }
            case REDIRECT: {
                Type[] invoked = Type.getArgumentTypes(binding.invokeDesc);
                int rest = params.length - self;
                int receiver = rest - invoked.length;
                if (receiver < 0 || receiver > 1) {
                    return false;
                }
                for (int i = 0; i < invoked.length; i++) {
                    if (!Type.getType(params[self + receiver + i]).equals(invoked[i])) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return false;
        }
    }

    private static boolean argumentTypesMatch(Class<?>[] params, int offset, Type[] args) {
        for (int i = 0; i < args.length; i++) {
            if (!Type.getType(params[offset + i]).equals(args[i])) {
                return false;
            }
        }
        return true;
    }

    private String expectedShape(PatchPlan.Binding binding) {
        if (binding.explicitDesc != null) {
            return " " + binding.explicitDesc;
        }
        return switch (binding.kind) {
            case INJECT, OVERWRITE -> " with the descriptor inferred from the handler";
            case REDIRECT -> " with arguments matching " + binding.invokeDesc;
            case MODIFY_VARIABLE -> " with arguments inferred from the handler";
        };
    }

    private String overloadHint(PatchPlan.Binding binding) {
        List<String> others = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(binding.targetName)) {
                others.add(method.name + method.desc);
            }
        }
        return others.isEmpty() ? "" : " (overloads in class: " + String.join(", ", others) + ")";
    }

    private static String atName(At at) {
        return at == null ? "?" : at.value().name();
    }

    private static String methodNames(List<MethodNode> methods) {
        List<String> names = new ArrayList<>();
        for (MethodNode method : methods) {
            names.add(method.name + method.desc);
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------
    // @Inject
    // ------------------------------------------------------------------

    private void applyInject(PatchPlan.Binding binding, MethodNode method) {
        List<Site> sites = sites(method, binding.at);
        if (sites.isEmpty()) {
            throw new PatchException("no " + atName(binding.at) + " injection point");
        }
        Type returnType = Type.getReturnType(method.desc);
        int self = AsmSupport.isStatic(method) ? 0 : 1;
        int expected = self + Type.getArgumentTypes(method.desc).length + 1;
        if (binding.params.length != expected) {
            throw new PatchException("handler takes " + binding.params.length + " parameter(s), target needs "
                    + expected + (self == 1 ? " (self + args + CallbackInfo)" : " (args + CallbackInfo)"));
        }
        if (binding.ciReturnable) {
            if (!binding.acceptsReturn(returnType)) {
                throw new PatchException("handler declares CallbackInfoReturnable<"
                        + binding.ciValueType.getClassName() + "> but the target returns "
                        + returnType.getClassName());
            }
        } else if (binding.cancellable && returnType != Type.VOID_TYPE) {
            throw new PatchException("a cancellable @Inject on a non-void method"
                    + " must take CallbackInfoReturnable<R>");
        }
        boolean valueOnStack = binding.at.value() == At.Type.RETURN || binding.at.value() == At.Type.TAIL;
        for (Site site : sites) {
            checkConstructorOrder(method, site);
            insert(method, site, injectPayload(binding, method, valueOnStack));
        }
    }

    private InsnList injectPayload(PatchPlan.Binding binding, MethodNode method, boolean valueOnStack) {
        InsnList out = new InsnList();
        Type returnType = Type.getReturnType(method.desc);
        int returnSlot = -1;
        if (valueOnStack && returnType.getSize() > 0) {
            returnSlot = allocate(method, returnType.getSize());
            out.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), returnSlot));
        }
        AsmSupport.pushSelfAndArgs(out, method);
        String callbackOwner = binding.ciReturnable
                ? AsmSupport.CALLBACK_INFO_RETURNABLE : AsmSupport.CALLBACK_INFO;
        out.add(new TypeInsnNode(Opcodes.NEW, callbackOwner));
        out.add(new InsnNode(Opcodes.DUP));
        out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, callbackOwner, "<init>", "()V", false));
        if (valueOnStack && returnType.getSize() > 0 && binding.ciReturnable) {
            // Mimic Mixin: a RETURN/TAIL handler must be able to read the callback's
            // "return value", which is the value the target method is about to return.
            out.add(new InsnNode(Opcodes.DUP));
            out.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnSlot));
            AsmSupport.box(out, returnType);
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AsmSupport.CALLBACK_INFO_RETURNABLE,
                    "setReturnValue", "(Ljava/lang/Object;)V", false));
        }
        if (!binding.cancellable) {
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.handlerOwner,
                    binding.handlerName, binding.handlerDesc, false));
        } else {
            int callbackSlot = allocate(method, 1);
            out.add(new InsnNode(Opcodes.DUP));
            out.add(new VarInsnNode(Opcodes.ASTORE, callbackSlot));
            out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.handlerOwner,
                    binding.handlerName, binding.handlerDesc, false));
            LabelNode notCancelled = new LabelNode();
            out.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, callbackOwner,
                    "isCancelled", AsmSupport.IS_CANCELLED_DESC, false));
            out.add(new JumpInsnNode(Opcodes.IFEQ, notCancelled));
            if (returnType == Type.VOID_TYPE) {
                out.add(new InsnNode(Opcodes.RETURN));
            } else {
                out.add(new VarInsnNode(Opcodes.ALOAD, callbackSlot));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AsmSupport.CALLBACK_INFO_RETURNABLE,
                        "getReturnValue", AsmSupport.GET_RETURN_VALUE_DESC, false));
                AsmSupport.unbox(out, returnType);
                out.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            }
            out.add(notCancelled);
        }
        if (valueOnStack && returnType.getSize() > 0) {
            out.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), returnSlot));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // @Redirect
    // ------------------------------------------------------------------

    private void applyRedirect(PatchPlan.Binding binding, MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call
                    && (binding.invokeOwner.isEmpty() || call.owner.equals(binding.invokeOwner))
                    && (binding.invokeName == null || binding.invokeName.isEmpty()
                        || call.name.equals(binding.invokeName))
                    && call.desc.equals(binding.invokeDesc)) {
                calls.add(call);
            }
        }
        String what = (binding.invokeOwner.isEmpty() ? "<any owner>" : binding.invokeOwner)
                + binding.invokeDesc;
        if (calls.size() <= binding.ordinal) {
            throw new PatchException("found " + calls.size() + " invocation(s) of " + what
                    + " but ordinal() is " + binding.ordinal);
        }
        MethodInsnNode call = calls.get(binding.ordinal);
        if ("<init>".equals(call.name)) {
            throw new PatchException("cannot redirect a constructor invocation");
        }
        boolean invokeStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        boolean self = !AsmSupport.isStatic(method);
        Type[] args = Type.getArgumentTypes(binding.invokeDesc);
        int rest = binding.params.length - (self ? 1 : 0);
        int receiver = rest - args.length;
        if (receiver < 0 || receiver > 1) {
            throw new PatchException("handler takes " + binding.params.length + " parameter(s), which does not"
                    + " match " + (self ? "self + " : "") + (invokeStatic ? "" : "receiver + ")
                    + args.length + " invocation argument(s)");
        }
        if ((receiver == 1) == invokeStatic) {
            throw new PatchException(invokeStatic
                    ? "handler declares a receiver but the invocation is static"
                    : "handler is missing the receiver parameter for an instance invocation");
        }
        checkConstructorOrder(method, new Site(call));

        InsnList out = new InsnList();
        int[] slots = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            int slot = allocate(method, args[i].getSize());
            slots[i] = slot;
            out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), slot));
        }
        int receiverSlot = -1;
        if (!invokeStatic) {
            receiverSlot = allocate(method, 1);
            out.add(new VarInsnNode(Opcodes.ASTORE, receiverSlot));
        }
        if (self) {
            out.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        int parameter = self ? 1 : 0;
        if (!invokeStatic) {
            out.add(new VarInsnNode(Opcodes.ALOAD, receiverSlot));
            AsmSupport.coerceReference(out, Type.getObjectType(call.owner), Type.getType(binding.params[parameter]));
            parameter++;
        }
        for (int i = 0; i < args.length; i++) {
            out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slots[i]));
            AsmSupport.coerceReference(out, args[i], Type.getType(binding.params[parameter + i]));
        }
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.handlerOwner,
                binding.handlerName, binding.handlerDesc, false));
        method.instructions.insertBefore(call, out);
        method.instructions.remove(call);
    }

    // ------------------------------------------------------------------
    // @ModifyVariable
    // ------------------------------------------------------------------

    private void applyModifyVariable(PatchPlan.Binding binding, MethodNode method) {
        if (binding.at.value() != At.Type.HEAD) {
            throw new PatchException("only At.Type.HEAD is supported for @ModifyVariable");
        }
        Site site = headSite(method);
        checkConstructorOrder(method, site);
        Type[] args = Type.getArgumentTypes(method.desc);
        int self = AsmSupport.isStatic(method) ? 0 : 1;
        int expected = self + args.length + 1;
        if (binding.params.length != expected) {
            throw new PatchException("handler takes " + binding.params.length + " parameter(s), target needs "
                    + expected + " (self + args + value)");
        }

        int slot;
        Type slotType;
        if (binding.varIndex >= 0) {
            slot = binding.varIndex;
            slotType = AsmSupport.parameterType(method, className, slot);
            if (slotType == null) {
                throw new PatchException("slot " + slot + " is not a parameter slot;"
                        + " @ModifyVariable only rewrites parameter slots at HEAD");
            }
            if (!AsmSupport.sameSort(slotType, binding.valueType)) {
                throw new PatchException("slot " + slot + " holds " + slotType.getClassName()
                        + " but the handler works on " + binding.valueType.getClassName());
            }
        } else {
            List<Integer> candidates = parameterSlots(method, binding.valueType);
            if (candidates.size() <= binding.varOrdinal) {
                throw new PatchException("no parameter of type " + binding.valueType.getClassName()
                        + " at ordinal " + binding.varOrdinal + " (found " + candidates.size() + ")");
            }
            slot = candidates.get(binding.varOrdinal);
            slotType = AsmSupport.parameterType(method, className, slot);
        }

        InsnList out = new InsnList();
        AsmSupport.pushSelfAndArgs(out, method);
        out.add(new VarInsnNode(slotType.getOpcode(Opcodes.ILOAD), slot));
        AsmSupport.coerceReference(out, slotType, binding.valueType);
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.handlerOwner,
                binding.handlerName, binding.handlerDesc, false));
        AsmSupport.coerceReference(out, binding.valueType, slotType);
        out.add(new VarInsnNode(slotType.getOpcode(Opcodes.ISTORE), slot));
        insert(method, site, out);
    }

    private static List<Integer> parameterSlots(MethodNode method, Type wanted) {
        List<Integer> exact = new ArrayList<>();
        List<Integer> loose = new ArrayList<>();
        int slot = AsmSupport.isStatic(method) ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.equals(wanted)) {
                exact.add(slot);
            } else if (AsmSupport.sameSort(argument, wanted)) {
                loose.add(slot);
            }
            slot += argument.getSize();
        }
        return exact.isEmpty() ? loose : exact;
    }

    // ------------------------------------------------------------------
    // @Overwrite
    // ------------------------------------------------------------------

    private void applyOverwrite(PatchPlan.Binding binding, MethodNode method) {
        if (!overwritten.add(method)) {
            throw new PatchException("method is already overwritten by another handler");
        }
        if ("<init>".equals(method.name)) {
            throw new PatchException("@Overwrite on a constructor is not supported"
                    + " (the handler would receive an uninitialised this)");
        }
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            throw new PatchException("@Overwrite needs a method with a bytecode body");
        }
        int self = AsmSupport.isStatic(method) ? 0 : 1;
        int expected = self + Type.getArgumentTypes(method.desc).length;
        if (binding.params.length != expected) {
            throw new PatchException("handler takes " + binding.params.length + " parameter(s), target needs "
                    + expected + (self == 1 ? " (self + args)" : " (args)"));
        }
        Type returnType = Type.getReturnType(method.desc);
        if (!binding.handlerReturn.equals(returnType)) {
            throw new PatchException("handler returns " + binding.handlerReturn.getClassName()
                    + " but the target returns " + returnType.getClassName());
        }

        method.instructions.clear();
        if (method.tryCatchBlocks != null) {
            method.tryCatchBlocks.clear();
        }
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        if (method.visibleLocalVariableAnnotations != null) {
            method.visibleLocalVariableAnnotations.clear();
        }
        if (method.invisibleLocalVariableAnnotations != null) {
            method.invisibleLocalVariableAnnotations.clear();
        }
        InsnList out = new InsnList();
        AsmSupport.pushSelfAndArgs(out, method);
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, binding.handlerOwner,
                binding.handlerName, binding.handlerDesc, false));
        if (returnType == Type.VOID_TYPE) {
            out.add(new InsnNode(Opcodes.RETURN));
        } else {
            out.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }
        method.instructions.add(out);
    }

    // ------------------------------------------------------------------
    // injection points
    // ------------------------------------------------------------------

    /** An insertion point; {@code before == null} means "append at the end of the method". */
    private static final class Site {
        final AbstractInsnNode before;

        Site(AbstractInsnNode before) {
            this.before = before;
        }
    }

    private List<Site> sites(MethodNode method, At at) {
        List<Site> out = new ArrayList<>();
        Type returnType = Type.getReturnType(method.desc);
        switch (at.value()) {
            case HEAD -> out.add(headSite(method));
            case RETURN -> {
                int opcode = returnType.getOpcode(Opcodes.IRETURN);
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() == opcode) {
                        out.add(new Site(insn));
                    }
                }
            }
            case TAIL -> {
                int opcode = returnType.getOpcode(Opcodes.IRETURN);
                AbstractInsnNode last = null;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() == opcode) {
                        last = insn;
                    }
                }
                if (last != null) {
                    out.add(new Site(last));
                }
            }
            case INVOKE -> {
                String owner = AsmSupport.normalizeOwner(at.method());
                String name = at.name().trim();
                String desc = at.desc().trim();
                if (desc.isEmpty()) {
                    throw new PatchException("@At(INVOKE) requires desc()");
                }
                int ordinal = at.ordinal();
                int seen = 0;
                for (AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof MethodInsnNode call
                            && (owner.isEmpty() || call.owner.equals(owner))
                            && (name.isEmpty() || call.name.equals(name))
                            && call.desc.equals(desc)) {
                        if (seen == ordinal) {
                            out.add(new Site(insn));
                            break;
                        }
                        seen++;
                    }
                }
            }
        }
        return out;
    }

    private Site headSite(MethodNode method) {
        if (method.instructions.size() == 0) {
            throw new PatchException("method has no bytecode (abstract or native)");
        }
        if (!"<init>".equals(method.name)) {
            return new Site(method.instructions.getFirst());
        }
        AbstractInsnNode superCall = findSuperCall(method);
        if (superCall == null) {
            throw new PatchException("constructor has no super()/this() call to inject after");
        }
        return new Site(superCall.getNext());
    }

    private void checkConstructorOrder(MethodNode method, Site site) {
        if (!"<init>".equals(method.name)) {
            return;
        }
        AbstractInsnNode superCall = findSuperCall(method);
        if (superCall == null) {
            throw new PatchException("constructor has no super()/this() call");
        }
        if (site.before == null) {
            return;
        }
        int superIndex = method.instructions.indexOf(superCall);
        int siteIndex = method.instructions.indexOf(site.before);
        if (siteIndex <= superIndex) {
            throw new PatchException("injection point precedes the super()/this() call,"
                    + " where this is not initialised yet");
        }
    }

    private AbstractInsnNode findSuperCall(MethodNode method) {
        AbstractInsnNode fallback = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() != Opcodes.INVOKESPECIAL) {
                continue;
            }
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"<init>".equals(call.name)) {
                continue;
            }
            if (call.owner.equals(classNode.name) || call.owner.equals(classNode.superName)) {
                return call;
            }
            if (fallback == null) {
                fallback = call;
            }
        }
        return fallback;
    }

    private static void insert(MethodNode method, Site site, InsnList insns) {
        if (site.before == null) {
            method.instructions.add(insns);
        } else {
            method.instructions.insertBefore(site.before, insns);
        }
    }

    private static int allocate(MethodNode method, int size) {
        int slot = method.maxLocals;
        method.maxLocals += Math.max(size, 1);
        return slot;
    }
}