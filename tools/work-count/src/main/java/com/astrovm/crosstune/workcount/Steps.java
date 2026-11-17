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
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Writes the steps of {@link Work} into each class it counts in, and the elements of each array made, cloned or
 * copied into every class: those loaded from now on, and those loaded before.
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
        try {
            instrumentation.retransformClasses(Thread.class);
        } catch (UnmodifiableClassException error) {
            failed.add(String.valueOf(error));
        }
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
            if (!instrumentation.isModifiableClass(loaded) || !Counted.in(name)) continue;
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
        if (name == null) return null;
        if (name.equals(THREAD)) return ending(bytes);
        if (!Counted.in(name)) return null;
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

    /** The class of each platform thread, whose {@code exit} method the JVM calls on each as it ends. */
    private static final String THREAD = "java/lang/Thread";

    /**
     * The thread class, with {@link Work#ended} at the start of {@code exit}, and no steps: it doesn't count. A thread
     * that ends is gone with the processor time it took, so it keeps it there.
     */
    private byte[] ending(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            boolean exits = false;
            for (MethodNode method : node.methods) {
                if (method.name.equals("exit") && method.desc.equals("()V") && method.instructions.size() > 0) {
                    method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "ended", "()V", false));
                    exits = true;
                }
            }
            if (!exits) throw new IllegalStateException("no exit(), which the JVM calls as a thread ends");
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError error) {
            synchronized (failed) {
                failed.add(THREAD.concat(": ").concat(String.valueOf(error)));
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
        for (MethodNode method : node.methods) count(method, counts(method));
        if (node.name.equals("java/util/regex/Matcher")) phoneInputs(node);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /**
     * Android copies the input when a matcher resets or changes pattern. The JDK keeps its reference.
     * Count that phone work here, including calls through handles. A region and find(start) call reset.
     */
    private static void phoneInputs(ClassNode node) {
        Set<String> found = new HashSet<>();
        Set<String> wanted = Set.of("reset()Ljava/util/regex/Matcher;", "usePattern(Ljava/util/regex/Pattern;)Ljava/util/regex/Matcher;");
        for (MethodNode method : node.methods) {
            String signature = method.name.concat(method.desc);
            if (!wanted.contains(signature)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.ARETURN) continue;
                InsnList input = new InsnList();
                input.add(new VarInsnNode(Opcodes.ALOAD, 0));
                input.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "text", "Ljava/lang/CharSequence;"));
                input.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "regexInput", "(Ljava/lang/CharSequence;)V", false));
                method.instructions.insertBefore(instruction, input);
                found.add(signature);
            }
        }
        if (!found.equals(wanted)) throw new IllegalStateException("No matcher input-copy count for ".concat(String.valueOf(wanted)));
    }

    /**
     * Whether a method's steps count. Not where the compiler can swap the method for code of its own, which has no
     * steps in it: the same reading would count less from whenever the compiler gets to it. Each of those the JVM
     * swaps, with C1 alone and the vectorized mismatch and CRC32C turned off, takes the same work each time, as it
     * never loops: where one is called again and again, the loop that calls it counts. Its elements count all the
     * same: C1 swaps none that makes or copies an array.
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

    /**
     * One method's work. Where [steps], one step at its start and one before each jump back. And everywhere, the
     * elements of each array it makes, clones or copies, which the JVM goes through in code of its own, and of each
     * call that does the same in a class that doesn't count ({@link #GROWS}), and the frames of each stack trace taken.
     */
    private static void count(MethodNode method, boolean steps) {
        InsnList code = method.instructions;
        Set<LabelNode> passed = new HashSet<>();
        for (AbstractInsnNode element : code.toArray()) {
            if (element instanceof LabelNode) passed.add((LabelNode) element);
            if (steps && element instanceof JumpInsnNode && passed.contains(((JumpInsnNode) element).label)) code.insertBefore(element, step());
            int op = element.getOpcode();
            if (op == Opcodes.NEWARRAY || op == Opcodes.ANEWARRAY) {
                code.insertBefore(element, lengthOnTop(Type.INT_TYPE));
            } else if (op == Opcodes.MULTIANEWARRAY) {
                code.insertBefore(element, levels(method, ((MultiANewArrayInsnNode) element).dims));
            } else if (element instanceof InvokeDynamicInsnNode) {
                for (Object constant : ((InvokeDynamicInsnNode) element).bsmArgs) unreferenced(constant);
            } else if (element instanceof LdcInsnNode) {
                unreferenced(((LdcInsnNode) element).cst);
            } else if (element instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) element;
                String called = call.owner.concat(".").concat(call.name).concat(call.desc);
                int[] grows = GROWS.get(called);
                if (cloned(call.owner, call.name, call.desc)) {
                    code.insertBefore(call, new InsnNode(Opcodes.DUP));
                    code.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "cloned", "(Ljava/lang/Object;)V", false));
                } else if (called.equals(INTERN)) {
                    code.insertBefore(call, sized(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false)));
                } else if (grows != null) {
                    code.insertBefore(call, arguments(method, Type.getArgumentTypes(call.desc), grows));
                } else if (ZLIB.contains(call.owner) && (call.name.startsWith("inflate") || call.name.startsWith("deflate")) && call.desc.endsWith(")J")) {
                    InsnList read = new InsnList();
                    read.add(new InsnNode(Opcodes.DUP2));
                    read.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "zlib", "(J)V", false));
                    code.insert(call, read);
                } else if (called.equals(TRACED)) {
                    InsnList frames = new InsnList();
                    frames.add(new InsnNode(Opcodes.DUP));
                    frames.add(new FieldInsnNode(Opcodes.GETFIELD, "java/lang/Throwable", "depth", "I"));
                    frames.add(new InsnNode(Opcodes.I2L));
                    frames.add(elements("(J)V"));
                    code.insert(call, frames);
                }
            }
        }
        if (steps) code.insert(step());
    }

    /** Whether the call clones, which an array's clone does: Kotlin calls it as Object's. */
    private static boolean cloned(String owner, String name, String descriptor) {
        return name.equals("clone") && descriptor.equals("()Ljava/lang/Object;") && (owner.startsWith("[") || owner.equals("java/lang/Object"));
    }

    /**
     * Fails where a constant is a method handle to a call counted where it's called: a method reference, made by the
     * JVM in a class of its own, which isn't written, would call it with nothing counted. No class in the JDK, Kotlin,
     * the app or its libraries has one.
     */
    private static void unreferenced(Object constant) {
        if (constant instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) constant;
            unreferenced(dynamic.getBootstrapMethod());
            for (int each = 0; each < dynamic.getBootstrapMethodArgumentCount(); each++) unreferenced(dynamic.getBootstrapMethodArgument(each));
        }
        if (!(constant instanceof Handle)) return;
        Handle handle = (Handle) constant;
        String called = handle.getOwner().concat(".").concat(handle.getName()).concat(handle.getDesc());
        if (GROWS.containsKey(called) || called.equals(INTERN) || cloned(handle.getOwner(), handle.getName(), handle.getDesc())) {
            throw new IllegalStateException("a method handle to ".concat(called).concat(", whose work nothing would count"));
        }
    }

    /**
     * The calls whose work grows with their arguments, which ones, and that do it where nothing counts: in the JVM's
     * own code, or in a class that doesn't count. A checksum grows with what goes in; zlib's squeeze and unsqueeze are
     * counted by what they give back ({@link #ZLIB}), and a stack trace by its frames ({@link #TRACED}), whose elements
     * a class that doesn't count makes. The rest of the JVM's own code that a counted class calls takes the same work
     * each time, as a hash of an object or a look at a field, or does what no reader here does, as a file read or a
     * process started: that work isn't counted.
     */
    private static final Map<String, int[]> GROWS = Map.ofEntries(
        Map.entry("java/lang/System.arraycopy(Ljava/lang/Object;ILjava/lang/Object;II)V", at(4)),
        Map.entry("java/lang/reflect/Array.newInstance(Ljava/lang/Class;I)Ljava/lang/Object;", at(1)),
        Map.entry("java/lang/reflect/Array.newInstance(Ljava/lang/Class;[I)Ljava/lang/Object;", at(1)),
        Map.entry("jdk/internal/misc/Unsafe.allocateUninitializedArray(Ljava/lang/Class;I)Ljava/lang/Object;", at(1)),
        Map.entry("jdk/internal/misc/Unsafe.copyMemory(Ljava/lang/Object;JLjava/lang/Object;JJ)V", at(4)),
        Map.entry("jdk/internal/misc/Unsafe.copyMemory(JJJ)V", at(2)),
        Map.entry("jdk/internal/misc/Unsafe.setMemory(Ljava/lang/Object;JJB)V", at(2)),
        Map.entry("jdk/internal/misc/Unsafe.setMemory(JJB)V", at(1)),
        Map.entry("jdk/internal/misc/ScopedMemoryAccess.copyMemory(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JJ)V", at(6)),
        Map.entry("jdk/internal/misc/ScopedMemoryAccess.copySwapMemory(Ljdk/internal/foreign/MemorySessionImpl;Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JLjava/lang/Object;JJJ)V", at(6)),
        Map.entry("jdk/internal/misc/ScopedMemoryAccess.setMemory(Ljdk/internal/foreign/MemorySessionImpl;Ljava/lang/Object;JJB)V", at(3)),
        Map.entry("java/lang/StackTraceElement.of(Ljava/lang/Object;I)[Ljava/lang/StackTraceElement;", at(1)),
        Map.entry("java/lang/StackTraceElement.of(Ljava/lang/Throwable;I)[Ljava/lang/StackTraceElement;", at(1)),
        Map.entry("java/util/zip/CRC32.updateBytes0(I[BII)I", at(3)),
        Map.entry("java/util/zip/CRC32.updateByteBuffer0(IJII)I", at(3)),
        Map.entry("java/util/zip/Adler32.updateBytes(I[BII)I", at(3)),
        Map.entry("java/util/zip/Adler32.updateByteBuffer(IJII)I", at(3)),
        Map.entry("java/util/zip/Inflater.setDictionary(J[BII)V", at(3)),
        Map.entry("java/util/zip/Inflater.setDictionaryBuffer(JJI)V", at(2)),
        Map.entry("java/util/zip/Deflater.setDictionary(J[BII)V", at(3)),
        Map.entry("java/util/zip/Deflater.setDictionaryBuffer(JJI)V", at(2))
    );

    /** The classes whose natives squeeze and unsqueeze, each counted after by the bytes it read and wrote. */
    private static final Set<String> ZLIB = Set.of("java/util/zip/Inflater", "java/util/zip/Deflater");

    /**
     * Where an exception takes the stack trace, in the JVM's own code, which walks each frame of the stack: counted
     * after, by how many frames it holds, as the exception keeps in {@code depth}. The JVM walks at most
     * {@code MaxJavaStackTraceDepth} frames, 1024 by default.
     */
    private static final String TRACED = "java/lang/Throwable.fillInStackTrace(I)Ljava/lang/Throwable;";

    /** A string the JVM keeps one of each of, which it hashes and compares in code of its own. */
    private static final String INTERN = "java/lang/String.intern()Ljava/lang/String;";

    /** Counts the size of what's on top of the stack, as [size] reads it, and leaves it there. */
    private static InsnList sized(AbstractInsnNode size) {
        InsnList sized = new InsnList();
        sized.add(new InsnNode(Opcodes.DUP));
        sized.add(size);
        sized.add(new InsnNode(Opcodes.I2L));
        sized.add(elements("(J)V"));
        return sized;
    }

    /** Counts the length on top of the stack, of [type], and leaves it there. */
    private static InsnList lengthOnTop(Type type) {
        InsnList length = new InsnList();
        length.add(new InsnNode(type.getSize() == 2 ? Opcodes.DUP2 : Opcodes.DUP));
        length.add(length(type));
        return length;
    }

    /** Counts the length on top of the stack, of [type], and takes it off. */
    private static InsnList length(Type type) {
        InsnList length = new InsnList();
        if (type.getSort() == Type.ARRAY) {
            length.add(elements("([I)V"));
        } else if (type.getSize() == 2) {
            length.add(elements("(J)V"));
        } else {
            length.add(new InsnNode(Opcodes.I2L));
            length.add(elements("(J)V"));
        }
        return length;
    }

    /** The arguments to count, lowest first. */
    private static int[] at(int... which) {
        return which;
    }

    /**
     * Counts a call's arguments [which] of [arguments], lowest first, under those after the lowest, which go to locals
     * of their own past the method's and back.
     */
    private static InsnList arguments(MethodNode method, Type[] arguments, int[] which) {
        InsnList counted = new InsnList();
        int lowest = which[0];
        int[] slots = new int[arguments.length];
        int free = method.maxLocals;
        for (int i = lowest + 1; i < arguments.length; i++) {
            slots[i] = free;
            free += arguments[i].getSize();
        }
        method.maxLocals = Math.max(method.maxLocals, free);
        for (int i = arguments.length - 1; i > lowest; i--) counted.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE), slots[i]));
        counted.add(lengthOnTop(arguments[lowest]));
        for (int k = 1; k < which.length; k++) {
            counted.add(new VarInsnNode(arguments[which[k]].getOpcode(Opcodes.ILOAD), slots[which[k]]));
            counted.add(length(arguments[which[k]]));
        }
        for (int i = lowest + 1; i < arguments.length; i++) counted.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]));
        return counted;
    }

    /** Counts what each level of a new array of [dims] levels holds, from the lengths on the stack, and leaves them there. */
    private static InsnList levels(MethodNode method, int dims) {
        InsnList counted = new InsnList();
        int free = method.maxLocals;
        method.maxLocals = free + dims;
        for (int i = dims - 1; i >= 0; i--) counted.add(new VarInsnNode(Opcodes.ISTORE, free + i));
        counted.add(new LdcInsnNode(dims));
        counted.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int i = 0; i < dims; i++) {
            counted.add(new InsnNode(Opcodes.DUP));
            counted.add(new LdcInsnNode(i));
            counted.add(new VarInsnNode(Opcodes.ILOAD, free + i));
            counted.add(new InsnNode(Opcodes.IASTORE));
        }
        counted.add(elements("([I)V"));
        for (int i = 0; i < dims; i++) counted.add(new VarInsnNode(Opcodes.ILOAD, free + i));
        return counted;
    }

    private static MethodInsnNode step() {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "step", "()V", false);
    }

    private static MethodInsnNode elements(String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, WORK, "elements", descriptor, false);
    }
}
