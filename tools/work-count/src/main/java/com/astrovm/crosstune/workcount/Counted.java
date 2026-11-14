package com.astrovm.crosstune.workcount;

/**
 * Which classes count: every class, in every module and class loader, but those that run the JVM, the agent and the
 * writing of classes.
 */
final class Counted {
    /**
     * What runs the JVM, the agent and the writing of classes: work no reading does as it reads, or none that grows
     * with what it reads. And {@code jdk/internal/misc}, as the JVM's first compiler swaps the atomic updates of its
     * {@code Unsafe}, which loop, for code of its own, so their steps would count until it gets to them. What a
     * reading allocates or copies there, it asks for from a class that counts, which counts it ({@link Steps}).
     */
    private static final String[] NOT_COUNTED = {
        "com/astrovm/crosstune/workcount/", "org/objectweb/asm/", "java/lang/invoke/", "java/lang/ref/", "java/lang/reflect/",
        "java/lang/classfile/", "java/lang/constant/", "java/lang/module/", "java/lang/instrument/", "java/lang/Thread", "java/lang/Object",
        "java/lang/Class", "java/lang/System", "java/lang/Module", "java/lang/StackTrace", "java/lang/StackWalker", "java/lang/ScopedValue",
        "java/lang/VirtualThread", "java/lang/Runtime", "java/lang/Shutdown", "java/util/jar/", "java/util/logging/",
        "java/util/ServiceLoader", "jdk/internal/misc/", "jdk/internal/loader/", "jdk/internal/reflect/", "jdk/internal/vm/",
        "jdk/internal/module/", "jdk/internal/classfile/", "jdk/internal/jimage/", "jdk/internal/perf/", "jdk/internal/ref/",
        "jdk/internal/event/", "jdk/internal/logger/", "jdk/jfr/", "jdk/internal/jfr/", "sun/instrument/", "sun/invoke/", "sun/reflect/",
    };

    private Counted() {
    }

    /**
     * Whether {@code name}'s work counts. A plain loop: a stream or a lambda here could load a class while one is
     * written, which the JDK would skip until {@link Steps#run}.
     */
    static boolean in(String name) {
        boolean counted = true;
        for (String start : NOT_COUNTED) counted &= !name.startsWith(start);
        return counted;
    }
}
