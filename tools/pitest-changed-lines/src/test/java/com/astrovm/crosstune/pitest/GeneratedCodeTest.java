package com.astrovm.crosstune.pitest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.function.Consumer;
import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Methods shaped like the ones Kotlin and Compose write, each with one check or return that's either theirs or the app's. */
public class GeneratedCodeTest {

    private static final String SCREEN = "com/astrovm/crosstune/ScreenKt";
    private static final String OBJECT = "java/lang/Object";
    private static final String LAMBDA = "kotlin/coroutines/jvm/internal/SuspendLambda";

    @Test
    public void aMaskOfWhatChangedIsCompose() {
        MethodNode method = checking(m -> {
            m.visitVarInsn(Opcodes.ILOAD, 2);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IAND);
        });
        assertTrue(generated(method, Opcodes.IAND));
        assertTrue(generated(method, Opcodes.IFEQ));
    }

    @Test
    public void theAppsOwnNumberIsntCompose() {
        MethodNode method = checking(m -> {
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IAND);
        });
        assertFalse(generated(method, Opcodes.IAND));
        assertFalse(generated(method, Opcodes.IFEQ));
    }

    @Test
    public void whetherAClassIsStableIsCompose() {
        MethodNode method = checking(m -> {
            m.visitIntInsn(Opcodes.BIPUSH, 6);
            m.visitFieldInsn(Opcodes.GETSTATIC, "androidx/compose/animation/core/Animatable", "$stable", "I");
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.ISHL);
            m.visitInsn(Opcodes.IOR);
        });
        assertTrue(generated(method, Opcodes.ISHL));
        assertTrue(generated(method, Opcodes.IOR));
    }

    @Test
    public void askingTheComposerWhetherToSkipIsCompose() {
        MethodNode method = checking(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "androidx/compose/runtime/Composer", "getSkipping", "()Z", true);
        });
        assertTrue(generated(method, Opcodes.IFEQ));
    }

    @Test
    public void askingTheAppIsntCompose() {
        MethodNode method = checking(m -> m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/astrovm/crosstune/Songs", "playing", "()Z", false));
        assertFalse(generated(method, Opcodes.IFEQ));
    }

    @Test
    public void aMaskKeptInALocalOfItsOwnIsStillCompose() {
        MethodNode method = checking(m -> {
            m.visitVarInsn(Opcodes.ILOAD, 2);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IOR);
            m.visitVarInsn(Opcodes.ISTORE, 3);
            m.visitVarInsn(Opcodes.ILOAD, 3);
        });
        assertTrue(generated(method, Opcodes.IFEQ));
    }

    @Test
    public void whetherACallSuspendedIsKotlins() {
        MethodNode method = returning(m -> {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/coroutines/intrinsics/IntrinsicsKt", "getCOROUTINE_SUSPENDED", "()Ljava/lang/Object;", false);
            m.visitVarInsn(Opcodes.ASTORE, 1);
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/astrovm/crosstune/Songs", "next", "()Ljava/lang/Object;", false);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            Label on = new Label();
            m.visitJumpInsn(Opcodes.IF_ACMPNE, on);
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitInsn(Opcodes.ARETURN);
            m.visitLabel(on);
            m.visitFrame(Opcodes.F_APPEND, 1, new Object[] {"java/lang/Object"}, 0, null);
            m.visitFieldInsn(Opcodes.GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;");
        }, "invokeSuspend");
        assertTrue(generated(method, LAMBDA, Opcodes.IF_ACMPNE, false));
        assertTrue(generated(method, LAMBDA, Opcodes.ARETURN, false));
        assertTrue(generated(method, LAMBDA, Opcodes.ARETURN, true));
    }

    @Test
    public void whatALambdaGivesBackIsTheApps() {
        MethodNode method = returning(m -> m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/astrovm/crosstune/Songs", "next", "()Ljava/lang/Object;", false), "invokeSuspend");
        assertFalse(generated(method, LAMBDA, Opcodes.ARETURN, false));
    }

    @Test
    public void aSuspendLambdasInvokeIsOnlyABridge() {
        MethodNode method = returning(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/astrovm/crosstune/ScreenKt$1", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }, "invoke");
        assertTrue(generated(method, LAMBDA, Opcodes.ARETURN, false));
    }

    @Test
    public void anotherClassesInvokeIsntABridge() {
        MethodNode method = returning(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitInsn(Opcodes.ACONST_NULL);
            m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/astrovm/crosstune/ScreenKt$1", "invokeSuspend", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        }, "invoke");
        assertFalse(generated(method, "kotlin/jvm/internal/Lambda", Opcodes.ARETURN, false));
    }

    @Test
    public void whatsRememberedIsTheApps() {
        MethodNode method = returning(m -> {
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "androidx/compose/runtime/Composer", "rememberedValue", "()Ljava/lang/Object;", true);
            m.visitVarInsn(Opcodes.ASTORE, 1);
            m.visitVarInsn(Opcodes.ALOAD, 1);
        }, "remembered");
        assertFalse(generated(method, OBJECT, Opcodes.ARETURN, false));
    }

    private static boolean generated(MethodNode method, int opcode) {
        return generated(method, OBJECT, opcode, false);
    }

    /** Whether the first, or [last], instruction of [opcode] in [method] of a class extending [superName] is Kotlin's or Compose's. */
    private static boolean generated(MethodNode method, String superName, int opcode, boolean last) {
        return GeneratedCode.of(SCREEN, superName, method).at(index(method, opcode, last));
    }

    /**
     * A composable that pushes an int with [body], then checks it: locals 0 to 2 are an int of the app's,
     * the Composer and the mask $changed, and local 3 an int named "kept".
     */
    private static MethodNode checking(Consumer<MethodNode> body) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "Screen", "(ILandroidx/compose/runtime/Composer;I)V", null, null);
        Label start = new Label();
        Label skip = new Label();
        Label end = new Label();
        m.visitCode();
        m.visitLabel(start);
        body.accept(m);
        m.visitJumpInsn(Opcodes.IFEQ, skip);
        m.visitInsn(Opcodes.NOP);
        m.visitLabel(skip);
        m.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        m.visitInsn(Opcodes.RETURN);
        m.visitLabel(end);
        m.visitLocalVariable("value", "I", null, start, end, 0);
        m.visitLocalVariable("$composer", "Landroidx/compose/runtime/Composer;", null, start, end, 1);
        m.visitLocalVariable("$changed", "I", null, start, end, 2);
        m.visitLocalVariable("kept", "I", null, start, end, 3);
        m.visitMaxs(4, 4);
        m.visitEnd();
        return m;
    }

    /** A method called [name], taking an object, that returns what [body] pushes. */
    private static MethodNode returning(Consumer<MethodNode> body, String name) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(4, 4);
        m.visitEnd();
        return m;
    }

    private static int index(MethodNode method, int opcode, boolean last) {
        int found = -1;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) {
                found = method.instructions.indexOf(insn);
                if (!last) return found;
            }
        }
        if (found < 0) throw new AssertionError("No " + opcode);
        return found;
    }
}
