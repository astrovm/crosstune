package com.astrovm.crosstune.workcount;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Writes the steps of {@link Work} into each class it counts in: those loaded from now on, and those loaded before.
 * Public, as the {@link Agent} makes it from a class loader of its own, with ASM.
 *
 * <p>The JDK gives a transformer no class that its thread loads while it writes another: that class is never written,
 * and says nothing. So each class given is kept, and {@link #run} writes each counted class loaded and never given.
 * {@link #get} names those, with each class that failed.
 */
public final class Steps implements ClassFileTransformer, Runnable, Supplier<List<String>> {
    private static final String WORK = "com/astrovm/crosstune/workcount/Work";
    private static final String INTRINSIC = "Ljdk/internal/vm/annotation/IntrinsicCandidate;";
    /** How many times {@link #run} writes the classes missed again, as writing them can load more. */
    private static final int ROUNDS = 10;

    private final Instrumentation instrumentation;

    /** Each class given, by name and class loader. */
    private final Set<String> given = new HashSet<>();

    /** Each class that failed to be written, with why. */
    private final List<String> failed = new ArrayList<>();

    /**
     * Writes this class once, so the classes that write classes are loaded before the first class is written, then
     * each counted class loaded so far.
     */
    public Steps(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
        try (InputStream in = Steps.class.getResourceAsStream("Steps.class")) {
            if (in == null) throw new IOException("No Steps.class");
            written(in.readAllBytes());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        instrumentation.addTransformer(this, true);
        run();
    }

    /** Writes each counted class loaded and not written yet. */
    @Override
    public void run() {
        for (int round = 0; round < ROUNDS; round++) {
            List<Class<?>> missed = missed();
            if (missed.isEmpty()) return;
            try {
                instrumentation.retransformClasses(missed.toArray(new Class<?>[0]));
            } catch (UnmodifiableClassException error) {
                synchronized (failed) {
                    failed.add(String.valueOf(error));
                }
                return;
            }
        }
    }

    /** Each class that should count and doesn't, with why: failed, or loaded and never given. */
    @Override
    public List<String> get() {
        List<String> uncounted = new ArrayList<>();
        synchronized (failed) {
            uncounted.addAll(failed);
        }
        for (Class<?> each : missed()) uncounted.add(each.getName().concat(": loaded while another class was written"));
        return uncounted;
    }

    private List<Class<?>> missed() {
        List<Class<?>> missed = new ArrayList<>();
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName().replace('.', '/');
            if (!instrumentation.isModifiableClass(loaded) || !Counted.in(loaded.getModule(), name)) continue;
            synchronized (given) {
                if (!given.contains(key(name, loaded.getClassLoader()))) missed.add(loaded);
            }
        }
        return missed;
    }

    /** Built with concat, not +: + makes a call site the JDK sets up with classes of its own, which a transform can't. */
    private static String key(String name, ClassLoader loader) {
        return name.concat("@").concat(String.valueOf(System.identityHashCode(loader)));
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
        if (name == null || !Counted.in(module, name)) return null;
        synchronized (given) {
            given.add(key(name, loader));
        }
        try {
            return written(bytes);
        } catch (RuntimeException | LinkageError error) {
            synchronized (failed) {
                failed.add(name.concat(": ").concat(String.valueOf(error)));
            }
            return null;
        }
    }

    /**
     * The class with its steps. Its stack map frames are kept as they are, as no step changes what's on the stack or
     * in the locals where a jump lands: so no class is loaded or read to work out a frame, which while a class is
     * written would wait on itself.
     */
    private static byte[] written(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        for (MethodNode method : node.methods) {
            if (counts(method)) count(method.instructions);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Whether a method's steps count. Not where the compiler can swap the method for code of its own, which has no
     * steps in it: the same reading would count less from whenever the compiler gets to it. Each of those the JVM
     * swaps, with C1 and the vectorized mismatch turned off, takes the same work each time, as it never loops: where
     * one is called again and again, the loop that calls it counts.
     */
    private static boolean counts(MethodNode method) {
        if (method.instructions.size() == 0) return false;
        boolean swapped = false;
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) swapped |= annotation.desc.equals(INTRINSIC);
        }
        return !swapped || loops(method.instructions);
    }

    /** Whether [code] jumps back. */
    private static boolean loops(InsnList code) {
        Set<LabelNode> passed = new HashSet<>();
        for (AbstractInsnNode element : code) {
            if (element instanceof LabelNode) passed.add((LabelNode) element);
            if (element instanceof JumpInsnNode && passed.contains(((JumpInsnNode) element).label)) return true;
        }
        return false;
    }

    /** One method's steps: one at its start, one before each jump back, and the length of each array copy. */
    private static void count(InsnList code) {
        Set<LabelNode> passed = new HashSet<>();
        for (AbstractInsnNode element : code.toArray()) {
            if (element instanceof LabelNode) passed.add((LabelNode) element);
            if (element instanceof JumpInsnNode && passed.contains(((JumpInsnNode) element).label)) code.insertBefore(element, step());
            if (element instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) element;
                if (call.owner.equals("java/lang/System") && call.name.equals("arraycopy")) {
                    code.insertBefore(call, new InsnNode(Opcodes.DUP));
                    code.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "copied", "(I)V", false));
                }
            }
        }
        code.insert(step());
    }

    private static MethodInsnNode step() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "step", "()V", false);
    }
}
