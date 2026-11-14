package com.astrovm.crosstune.workcount;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Puts a step of {@link Work} at the start of each method and before each jump back, and counts what each
 * {@code System.arraycopy} moves, in the classes {@link Steps#countsIn} names: those loaded from now on, and those the
 * JVM loaded before. Run the JVM with {@code -XX:TieredStopAtLevel=1 -XX:+UnlockDiagnosticVMOptions
 * -XX:-UseVectorizedMismatchIntrinsic}: C2 runs the JDK's text methods as code of its own, with no steps in it, from
 * whenever it gets to them, so the same reading would count differently each time. C1 swaps only methods that never
 * loop, which count nothing ({@link Steps}), and the vectorized mismatch, turned off. So a reading counts the same
 * steps compiled and interpreted.
 */
public final class Agent {
    private Agent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) throws Exception {
        Path jar = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // The JDK's classes call Work, so the class loader that loads them holds it, and java.base reads its module.
        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar.toFile()));
        Module work = Class.forName("com.astrovm.crosstune.workcount.Work", true, null).getModule();
        instrumentation.redefineModule(Object.class.getModule(), Set.of(work), Map.of(), Map.of(), Set.of(), Map.of());
        Steps steps = new Steps();
        // Once first, so the classes that write classes are loaded before any class waits on them to be written.
        steps.counted(Steps.class.getName().replace('.', '/'), Agent.class.getClassLoader(), Agent.class.getModule());
        instrumentation.addTransformer(steps, true);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (instrumentation.isModifiableClass(loaded) && Steps.countsIn(loaded.getModule(), loaded.getName().replace('.', '/'))) {
                instrumentation.retransformClasses(loaded);
            }
        }
    }

    /** Each class that should count and doesn't, with why, which leaves its work out of every count: a test fails on any. */
    public static List<String> uncounted() {
        synchronized (Steps.FAILED) {
            return List.copyOf(Steps.FAILED);
        }
    }
}
