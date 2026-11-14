package com.astrovm.crosstune.workcount;

/**
 * Which classes count. Apart from {@link Steps}, as the {@link Agent} the JVM starts asks too, from a class loader
 * without ASM.
 */
final class Counted {
    /** The app's, Kotlin's, and the JDK's that read and build text and hold what's read. */
    private static final String[] COUNTED = {"com/astrovm/crosstune/", "kotlin/", "java/lang/", "java/util/", "java/text/", "jdk/internal/icu/", "jdk/internal/util/"};

    /**
     * What runs the JVM, the agent and the writing of classes: work no reading does as it reads.
     */
    private static final String[] NOT_COUNTED = {
        "com/astrovm/crosstune/workcount/", "java/lang/invoke/", "java/lang/ref/", "java/lang/reflect/", "java/lang/classfile/", "java/lang/constant/",
        "java/lang/module/", "java/lang/instrument/", "java/lang/Thread", "java/lang/Object", "java/lang/Class", "java/lang/System", "java/lang/Module",
        "java/lang/StackTrace", "java/lang/StackWalker", "java/lang/ScopedValue", "java/lang/VirtualThread", "java/lang/Runtime", "java/lang/Shutdown",
        "java/util/concurrent/", "java/util/jar/", "java/util/zip/", "java/util/logging/", "java/util/ServiceLoader",
    };

    private Counted() {
    }

    /**
     * Whether {@code name}'s work counts. Plain loops: a stream or a lambda here could load a class while one is
     * written, which the JDK would skip until {@link Steps#run}.
     */
    static boolean in(Module module, String name) {
        if (module.isNamed() && !module.getName().equals("java.base")) return false;
        boolean counted = false;
        for (String start : COUNTED) counted |= name.startsWith(start);
        for (String start : NOT_COUNTED) counted &= !name.startsWith(start);
        return counted;
    }
}
