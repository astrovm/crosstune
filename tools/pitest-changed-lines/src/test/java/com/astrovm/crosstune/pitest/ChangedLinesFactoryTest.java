package com.astrovm.crosstune.pitest;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;
import org.pitest.plugin.FeatureSetting;
import org.pitest.plugin.ToggleStatus;

/** The CHANGED filter as PIT runs it, on a class shaped like Kotlin's CharSequence of its own, on a changed line. */
public class ChangedLinesFactoryTest {

    private static final String WORDS = "com/astrovm/crosstune/Words";
    private static final int LINE = 7;

    @Test
    public void onAChangedLineTheAppsMethodIsMutatedAndKotlinsBridgeToItIsnt() throws IOException {
        MutationInterceptor changed = interceptor(List.of(WORDS.substring(0, WORDS.lastIndexOf('/') + 1) + "Words.kt:" + LINE));
        changed.begin(new ClassTree(words()));
        List<MutationDetails> kept = List.copyOf(changed.intercept(List.of(returning("length"), returning("getLength")), null));
        changed.end();
        assertEquals(List.of("getLength"), kept.stream().map(MutationDetails::getMethod).collect(Collectors.toList()));
    }

    @Test
    public void onALineNotChangedNeitherIsMutated() throws IOException {
        MutationInterceptor changed = interceptor(List.of("com/astrovm/crosstune/Words.kt:" + (LINE + 1)));
        changed.begin(new ClassTree(words()));
        assertEquals(List.of(), List.copyOf(changed.intercept(List.of(returning("length"), returning("getLength")), null)));
        changed.end();
    }

    private static MutationInterceptor interceptor(List<String> lines) throws IOException {
        Path file = Files.createTempFile("changed", ".txt");
        file.toFile().deleteOnExit();
        Files.write(file, lines);
        FeatureSetting setting = new FeatureSetting("CHANGED", ToggleStatus.ACTIVATE, Map.of("file", List.of(file.toString())));
        return new ChangedLinesFactory().createInterceptor(new InterceptorParameters(setting, null, null, null, null, null));
    }

    /** A mutant of [method]'s return, on [LINE]. */
    private static MutationDetails returning(String method) {
        Location location = Location.location(ClassName.fromString(WORDS), method, "()I");
        return new MutationDetails(new MutationIdentifier(location, 1, "PRIMITIVE_RETURNS"), "Words.kt", "replaced int return with 0", LINE, 0);
    }

    /** As Kotlin writes it: getLength(), the app's, and length(), a public final bridge to it, not synthetic, both on [LINE]. */
    private static ClassNode words() {
        ClassNode words = new ClassNode();
        words.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, WORDS, null, "java/lang/Object", null);
        MethodNode getLength = (MethodNode) words.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "getLength", "()I", null, null);
        onLine(getLength, () -> getLength.visitInsn(Opcodes.ICONST_1));
        MethodNode length = (MethodNode) words.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_BRIDGE, "length", "()I", null, null);
        onLine(length, () -> {
            length.visitVarInsn(Opcodes.ALOAD, 0);
            length.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WORDS, "getLength", "()I", false);
        });
        words.visitEnd();
        return words;
    }

    private static void onLine(MethodNode method, Runnable body) {
        Label start = new Label();
        method.visitCode();
        method.visitLabel(start);
        method.visitLineNumber(LINE, start);
        body.run();
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
    }
}
