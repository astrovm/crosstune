package com.astrovm.crosstune.workcount;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/**
 * Puts a step of {@link Work} at the start of each method and before each jump back, and counts each element of an
 * array made, cloned or copied, and each frame of a stack trace, in the classes {@link Counted} names: those loaded from
 * now on, and those the JVM loaded before. And has each thread that ends keep the processor time it took, which
 * {@link Work#beside} adds up. Run the JVM with {@code -XX:TieredStopAtLevel=1 -XX:+UnlockDiagnosticVMOptions
 * -XX:-UseVectorizedMismatchIntrinsic -XX:-UseCRC32CIntrinsics}: C2 runs the JDK's text methods as code of its own,
 * with no steps in it, from whenever it gets to them, so the same reading would count differently each time. C1 swaps
 * only methods that never loop, which count no steps ({@link Steps}), and the vectorized mismatch and CRC32C, which
 * loop, turned off. So a reading counts the same steps compiled and interpreted.
 *
 * <p>Its argument is the paths of the jar with {@link Work} alone, then of the jars {@link Steps} needs, as
 * {@link File#pathSeparator} joins them. Work goes to the boot class loader, as the JDK's classes call it. Steps and
 * ASM get a class loader of their own, apart from the tests' classes, which may bring another ASM.
 */
public final class Agent {
    private static final String STEPS = "com.astrovm.crosstune.workcount.Steps";
    private static Supplier<?> uncounted;
    private static Runnable ready;

    private Agent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws ReflectiveOperationException, IOException {
        String[] jars = arguments.split(File.pathSeparator);
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jars[0]));
        Module work = Class.forName("com.astrovm.crosstune.workcount.Work", true, null).getModule();
        instrumentation.redefineModule(Object.class.getModule(), Set.of(work), Map.of(), Map.of(), Set.of(), Map.of());
        List<URL> writer = new ArrayList<>();
        writer.add(Agent.class.getProtectionDomain().getCodeSource().getLocation());
        for (int i = 1; i < jars.length; i++) writer.add(Path.of(jars[i]).toUri().toURL());
        ClassLoader loader = new URLClassLoader(writer.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        Object steps = loader.loadClass(STEPS).getConstructor(Instrumentation.class).newInstance(instrumentation);
        uncounted = (Supplier<?>) steps;
        ready = (Runnable) steps;
    }

    /** Writes each class that should count and was loaded while another class was written, which the JDK skips. */
    public static void ready() {
        ready.run();
    }

    /** Each class that should count and doesn't, with why, which leaves its work out of every count: a test fails on any. */
    public static List<?> uncounted() {
        return (List<?>) uncounted.get();
    }
}
