package com.astrovm.crosstune.pitest;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * What Kotlin and Compose add to the app's code rather than what it says, which no test of the app
 * can tell is changed:
 * - Compose's bit masks of which arguments changed ($changed, $dirty, $default) and of which classes
 *   are stable ($stable), and its checks on the Composer, like whether to skip. They only change how
 *   much gets drawn again.
 * - A coroutine's state machine: whether a call suspended, and the Unit it returns, which nothing reads.
 * Their mutants are described as any other's, like "negated conditional", so they're told by where
 * the values the mutated instruction takes come from.
 */
final class GeneratedCode {

    private static final Pattern MASK = Pattern.compile("\\$(changed|dirty|default)\\d*");
    private static final Set<String> COMPOSER = Set.of(
        "androidx/compose/runtime/Composer",
        "androidx/compose/runtime/Composer$Companion",
        "androidx/compose/runtime/ComposerKt"
    );
    private static final String SUSPEND_LAMBDA = "kotlin/coroutines/jvm/internal/SuspendLambda";

    private final MethodNode method;
    private final boolean suspendLambda;
    private final Frame<SourceValue>[] frames;

    /** For [method] of the class [owner], which extends [superName]; null when it can't be read. */
    static GeneratedCode of(String owner, String superName, MethodNode method) {
        try {
            return new GeneratedCode(method, SUSPEND_LAMBDA.equals(superName), new Analyzer<>(new SourceInterpreter()).analyze(owner, method));
        } catch (AnalyzerException e) {
            return null;
        }
    }

    private GeneratedCode(MethodNode method, boolean suspendLambda, Frame<SourceValue>[] frames) {
        this.method = method;
        this.suspendLambda = suspendLambda;
        this.frames = frames;
    }

    /** Whether the instruction at [index] works on what Kotlin or Compose keep rather than on the app's own values. */
    boolean at(int index) {
        return takesGenerated(method.instructions.get(index), new HashSet<>());
    }

    private boolean takesGenerated(AbstractInsnNode insn, Set<AbstractInsnNode> seen) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (frame == null) return false;
        int taken = taken(insn.getOpcode());
        for (int i = 1; i <= taken; i++) {
            if (generated(frame.getStack(frame.getStackSize() - i), seen)) return true;
        }
        return false;
    }

    private boolean generated(SourceValue value, Set<AbstractInsnNode> seen) {
        for (AbstractInsnNode source : value.insns) {
            if (!seen.add(source)) continue;
            if (kotlinsOwn(source) || composersOwn(source)) return true;
            if (source.getOpcode() == Opcodes.ILOAD && generated((VarInsnNode) source, seen)) return true;
            if (source.getOpcode() == Opcodes.ALOAD && heldKotlinsOwn((VarInsnNode) source)) return true;
            if (arithmetic(source.getOpcode()) && takesGenerated(source, seen)) return true;
        }
        return false;
    }

    /** Compose's: a call on the Composer, or whether a class is stable. */
    private static boolean composersOwn(AbstractInsnNode source) {
        if (source instanceof MethodInsnNode) return COMPOSER.contains(((MethodInsnNode) source).owner);
        return source instanceof FieldInsnNode && ((FieldInsnNode) source).name.equals("$stable");
    }

    /**
     * A coroutine's: the marker a call that suspended returns, and the Unit a lambda returns. In a
     * suspend lambda, its invoke is only a bridge to its invokeSuspend.
     */
    private boolean kotlinsOwn(AbstractInsnNode source) {
        if (source instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) source;
            return field.owner.equals("kotlin/Unit") && field.name.equals("INSTANCE");
        }
        if (!(source instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) source;
        return call.owner.equals("kotlin/coroutines/intrinsics/IntrinsicsKt") && call.name.equals("getCOROUTINE_SUSPENDED")
            || suspendLambda && method.name.equals("invoke") && call.name.equals("invokeSuspend");
    }

    /** A mask by its name, or an int kept in a local of its own, by what was stored there. */
    private boolean generated(VarInsnNode load, Set<AbstractInsnNode> seen) {
        if (MASK.matcher(nameOf(load)).matches()) return true;
        for (AbstractInsnNode store : storesTo(load)) {
            if (!seen.add(store)) continue;
            if (store.getOpcode() == Opcodes.ISTORE && takesGenerated(store, seen)) return true;
        }
        return false;
    }

    /**
     * An object local holding the coroutine's marker or Unit. Only those are followed into object
     * locals, as the app's own values come from Compose's calls too, like what's remembered.
     */
    private boolean heldKotlinsOwn(VarInsnNode load) {
        for (AbstractInsnNode store : storesTo(load)) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(store)];
            if (store.getOpcode() != Opcodes.ASTORE || frame == null) continue;
            for (AbstractInsnNode source : frame.getStack(frame.getStackSize() - 1).insns) {
                if (kotlinsOwn(source)) return true;
            }
        }
        return false;
    }

    private Set<AbstractInsnNode> storesTo(VarInsnNode load) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(load)];
        return frame == null ? Set.of() : frame.getLocal(load.var).insns;
    }

    private String nameOf(VarInsnNode load) {
        int at = method.instructions.indexOf(load);
        if (method.localVariables == null) return "";
        for (LocalVariableNode local : method.localVariables) {
            if (local.index == load.var && method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) return local.name;
        }
        return "";
    }

    /** How many values off the stack an instruction a mutant can change takes. */
    private static int taken(int opcode) {
        if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE) return 1;
        if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) return 1;
        if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE) return 2;
        if (opcode == Opcodes.ISTORE || opcode == Opcodes.ARETURN) return 1;
        return arithmetic(opcode) ? 2 : 0;
    }

    private static boolean arithmetic(int opcode) {
        switch (opcode) {
            case Opcodes.IADD:
            case Opcodes.ISUB:
            case Opcodes.IMUL:
            case Opcodes.IDIV:
            case Opcodes.IREM:
            case Opcodes.IAND:
            case Opcodes.IOR:
            case Opcodes.IXOR:
            case Opcodes.ISHL:
            case Opcodes.ISHR:
            case Opcodes.IUSHR:
                return true;
            default:
                return false;
        }
    }
}
