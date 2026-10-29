package com.astrovm.crosstune.pitest;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
 * What the Compose compiler adds to a composable, rather than what it says: the bit masks of which
 * arguments changed ($changed, $dirty, $default), and its checks on the Composer, like whether to
 * skip. Changing them only changes how much gets drawn again, which no test of the app can tell.
 * Their mutants are described as any other's, like "negated conditional", so they're told by where
 * the values the mutated instruction takes come from.
 */
final class ComposeBookkeeping {

    private static final Pattern MASK = Pattern.compile("\\$(changed|dirty|default)\\d*");
    private static final Set<String> COMPOSER = Set.of(
        "androidx/compose/runtime/Composer",
        "androidx/compose/runtime/Composer$Companion",
        "androidx/compose/runtime/ComposerKt"
    );

    private final MethodNode method;
    private final Frame<SourceValue>[] frames;

    /** For [method] of the class [owner], or null when it isn't a composable, which has nothing added. */
    static ComposeBookkeeping of(String owner, MethodNode method) {
        if (!method.desc.contains("Landroidx/compose/runtime/Composer;")) return null;
        try {
            return new ComposeBookkeeping(method, new Analyzer<>(new SourceInterpreter()).analyze(owner, method));
        } catch (AnalyzerException e) {
            return null;
        }
    }

    private ComposeBookkeeping(MethodNode method, Frame<SourceValue>[] frames) {
        this.method = method;
        this.frames = frames;
    }

    /** Whether the instruction at [index] works on what Compose keeps rather than on the app's own values. */
    boolean at(int index) {
        return takesFromBookkeeping(method.instructions.get(index), new HashSet<>());
    }

    private boolean takesFromBookkeeping(AbstractInsnNode insn, Set<AbstractInsnNode> seen) {
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (frame == null) return false;
        int taken = taken(insn.getOpcode());
        for (int i = 1; i <= taken; i++) {
            if (fromBookkeeping(frame.getStack(frame.getStackSize() - i), seen)) return true;
        }
        return false;
    }

    private boolean fromBookkeeping(SourceValue value, Set<AbstractInsnNode> seen) {
        for (AbstractInsnNode source : value.insns) {
            if (!seen.add(source)) continue;
            if (source instanceof MethodInsnNode && COMPOSER.contains(((MethodInsnNode) source).owner)) return true;
            if (source.getOpcode() == Opcodes.ILOAD && fromBookkeeping((VarInsnNode) source, seen)) return true;
            if (arithmetic(source.getOpcode()) && takesFromBookkeeping(source, seen)) return true;
        }
        return false;
    }

    /** A mask by its name, or an int the app keeps in a local of its own, by what was stored there. */
    private boolean fromBookkeeping(VarInsnNode load, Set<AbstractInsnNode> seen) {
        if (MASK.matcher(nameOf(load)).matches()) return true;
        Frame<SourceValue> frame = frames[method.instructions.indexOf(load)];
        if (frame == null) return false;
        for (AbstractInsnNode store : frame.getLocal(load.var).insns) {
            if (!seen.add(store)) continue;
            if (store.getOpcode() == Opcodes.ISTORE && takesFromBookkeeping(store, seen)) return true;
        }
        return false;
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
        if (opcode == Opcodes.ISTORE) return 1;
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
