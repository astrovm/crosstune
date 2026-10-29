package com.astrovm.crosstune.pitest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Methods shaped like the ones the Compose compiler writes, each with one check that's either its own or the app's. */
public class ComposeBookkeepingTest {

    private static final String COMPOSABLE = "(ILandroidx/compose/runtime/Composer;I)V";

    @Test
    public void aMaskOfWhatChangedIsCompose() {
        MethodNode method = composable(COMPOSABLE, m -> {
            m.visitVarInsn(Opcodes.ILOAD, 2);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IAND);
        }, "$changed");
        assertTrue(bookkeeping(method).at(index(method, Opcodes.IAND)));
        assertTrue(bookkeeping(method).at(index(method, Opcodes.IFEQ)));
    }

    @Test
    public void theAppsOwnNumberIsntCompose() {
        MethodNode method = composable(COMPOSABLE, m -> {
            m.visitVarInsn(Opcodes.ILOAD, 0);
            m.visitInsn(Opcodes.ICONST_3);
            m.visitInsn(Opcodes.IAND);
        }, "$changed");
        assertFalse(bookkeeping(method).at(index(method, Opcodes.IAND)));
        assertFalse(bookkeeping(method).at(index(method, Opcodes.IFEQ)));
    }

    @Test
    public void askingTheComposerWhetherToSkipIsCompose() {
        MethodNode method = composable(COMPOSABLE, m -> {
            m.visitVarInsn(Opcodes.ALOAD, 1);
            m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "androidx/compose/runtime/Composer", "getSkipping", "()Z", true);
        }, "$changed");
        assertTrue(bookkeeping(method).at(index(method, Opcodes.IFEQ)));
    }

    @Test
    public void askingTheAppIsntCompose() {
        MethodNode method = composable(COMPOSABLE, m -> {
            m.visitMethodInsn(Opcodes.INVOKESTATIC, "com/astrovm/crosstune/Songs", "playing", "()Z", false);
        }, "$changed");
        assertFalse(bookkeeping(method).at(index(method, Opcodes.IFEQ)));
    }

    @Test
    public void aMaskKeptInALocalOfItsOwnIsStillCompose() {
        MethodNode method = composable(COMPOSABLE, m -> {
            m.visitVarInsn(Opcodes.ILOAD, 2);
            m.visitInsn(Opcodes.ICONST_1);
            m.visitInsn(Opcodes.IOR);
            m.visitVarInsn(Opcodes.ISTORE, 3);
            m.visitVarInsn(Opcodes.ILOAD, 3);
        }, "$changed", "kept");
        assertTrue(bookkeeping(method).at(index(method, Opcodes.IFEQ)));
    }

    @Test
    public void aFunctionWithoutAComposerHasNoneOfIt() {
        MethodNode method = composable("(II)V", m -> m.visitVarInsn(Opcodes.ILOAD, 1), "$changed");
        assertNull(ComposeBookkeeping.of("com/astrovm/crosstune/ScreenKt", method));
    }

    private static ComposeBookkeeping bookkeeping(MethodNode method) {
        return ComposeBookkeeping.of("com/astrovm/crosstune/ScreenKt", method);
    }

    /**
     * A static method that pushes an int with [body], then checks it: locals 0 to 2 are an int of the app's,
     * the Composer and the int named [names][0], and local 3, if [names] has a second, is named that.
     */
    private static MethodNode composable(String desc, java.util.function.Consumer<MethodNode> body, String... names) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "Screen", desc, null, null);
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
        m.visitLocalVariable(names[0], "I", null, start, end, 2);
        if (names.length > 1) m.visitLocalVariable(names[1], "I", null, start, end, 3);
        m.visitMaxs(4, 4);
        m.visitEnd();
        return m;
    }

    private static int index(MethodNode method, int opcode) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == opcode) return method.instructions.indexOf(insn);
        }
        throw new AssertionError("No " + opcode);
    }
}
