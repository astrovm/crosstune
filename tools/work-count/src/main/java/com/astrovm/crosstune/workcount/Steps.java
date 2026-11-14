package com.astrovm.crosstune.workcount;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes the steps of {@link Work} into each class it counts in. Public, as the bootstrap class loader holds it, with
 * the rest of the agent's jar, apart from the {@link Agent} the JVM starts.
 */
public final class Steps implements ClassFileTransformer {
    private static final ClassDesc WORK = ClassDesc.of(Work.class.getName());
    private static final MethodTypeDesc STEP = MethodTypeDesc.of(ConstantDescs.CD_void);
    private static final MethodTypeDesc COPIED = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int);

    /** The app's, Kotlin's, and the JDK's that read and build text and hold what's read. */
    private static final String[] COUNTED = {"com/astrovm/crosstune/", "kotlin/", "java/lang/", "java/util/", "java/text/", "jdk/internal/icu/", "jdk/internal/util/"};

    /**
     * What runs the JVM, the agent and the writing of classes: work no reading does as it reads, and some of it runs
     * while a class is written, which would then wait on itself.
     */
    private static final String[] NOT_COUNTED = {
        "com/astrovm/crosstune/workcount/", "java/lang/invoke/", "java/lang/ref/", "java/lang/reflect/", "java/lang/classfile/", "java/lang/constant/",
        "java/lang/module/", "java/lang/instrument/", "java/lang/Thread", "java/lang/Object", "java/lang/Class", "java/lang/System", "java/lang/Module",
        "java/lang/StackTrace", "java/lang/StackWalker", "java/lang/ScopedValue", "java/lang/VirtualThread", "java/lang/Runtime", "java/lang/Shutdown",
        "java/util/concurrent/", "java/util/jar/", "java/util/zip/", "java/util/logging/", "java/util/ServiceLoader",
    };

    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);

    /** Each class not counted in, and why: a class loaded while another is written isn't, or it would wait on itself. */
    public static final List<String> FAILED = new ArrayList<>();

    /**
     * Whether {@code name}'s work counts. Plain loops: a stream or a lambda here could load a class while one is
     * written, which would wait on itself.
     */
    public static boolean countsIn(Module module, String name) {
        if (module.isNamed() && !module.getName().equals("java.base")) return false;
        boolean counted = false;
        for (String start : COUNTED) counted |= name.startsWith(start);
        for (String start : NOT_COUNTED) counted &= !name.startsWith(start);
        return counted;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (name == null || !countsIn(module, name)) return null;
        if (writing.get()) {
            synchronized (FAILED) {
                FAILED.add(name + ": loaded while another class was written");
            }
            return null;
        }
        writing.set(true);
        try {
            return written(bytes, loader);
        } catch (RuntimeException | LinkageError error) {
            synchronized (FAILED) {
                FAILED.add(name + ": " + error);
            }
            return null;
        } finally {
            writing.set(false);
        }
    }


    /** Writes [name] once, with the classes that do it, so they're loaded. */
    public void counted(String name, ClassLoader loader, Module module) throws IOException {
        try (InputStream in = loader.getResourceAsStream(name + ".class")) {
            if (in == null) throw new IOException("No " + name);
            written(in.readAllBytes(), loader);
        }
    }

    private static byte[] written(byte[] bytes, ClassLoader loader) {
        // The classes a class names are read as files, never loaded: one loaded while it's written would be defined twice.
        ClassHierarchyResolver system = ClassHierarchyResolver.ofResourceParsing(ClassLoader.getSystemClassLoader());
        ClassFile classes = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
            loader == null ? system : ClassHierarchyResolver.ofResourceParsing(loader).orElse(system)
        ));
        return classes.transformClass(
            classes.parse(bytes),
            ClassTransform.transformingMethods(Steps::counts, MethodTransform.transformingCode(CodeTransform.ofStateful(Counting::new)))
        );
    }

    /**
     * Whether a method's steps count. Not where the compiler can swap the method for code of its own, which has no
     * steps in it: the same reading would count less from whenever the compiler gets to it. Each of those the JVM
     * swaps, with C1 and the vectorized mismatch turned off, takes the same work each time, as it never loops: where
     * one is called again and again, the loop that calls it counts.
     */
    private static boolean counts(MethodModel method) {
        if (method.code().isEmpty()) return false;
        boolean swapped = false;
        for (RuntimeVisibleAnnotationsAttribute annotations : method.findAttributes(Attributes.runtimeVisibleAnnotations())) {
            for (Annotation annotation : annotations.annotations()) swapped |= annotation.className().equalsString(INTRINSIC);
        }
        return !swapped || loops(method.code().get());
    }

    /** Whether [code] jumps back. */
    private static boolean loops(CodeModel code) {
        Set<Label> passed = new HashSet<>();
        for (CodeElement element : code) {
            if (element instanceof LabelTarget target) passed.add(target.label());
            if (element instanceof BranchInstruction branch && passed.contains(branch.target())) return true;
        }
        return false;
    }

    private static final String INTRINSIC = "Ljdk/internal/vm/annotation/IntrinsicCandidate;";

    /** One method's steps: one at its start, one before each jump back, and the length of each array copy. */
    private static final class Counting implements CodeTransform {
        private final Set<Label> passed = new HashSet<>();
        private boolean started;

        @Override
        public void accept(CodeBuilder code, CodeElement element) {
            if (!started) {
                started = true;
                code.invokestatic(WORK, "step", STEP);
            }
            if (element instanceof LabelTarget target) passed.add(target.label());
            if (element instanceof BranchInstruction branch && passed.contains(branch.target())) code.invokestatic(WORK, "step", STEP);
            if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals("java/lang/System") && call.name().equalsString("arraycopy")) {
                code.dup();
                code.invokestatic(WORK, "copied", COPIED);
            }
            code.with(element);
        }
    }
}
