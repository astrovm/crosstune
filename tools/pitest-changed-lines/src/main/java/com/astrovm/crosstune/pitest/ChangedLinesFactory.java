package com.astrovm.crosstune.pitest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.plugin.Feature;
import org.pitest.plugin.FeatureParameter;

/**
 * Keeps the mutants on the lines listed in a file, one "com/astrovm/crosstune/File.kt:12" a line,
 * which scripts/mutants.py writes from a branch's diff. Lines Kotlin inlined from elsewhere are
 * numbered past the end of the file, so they're never on the list.
 */
public final class ChangedLinesFactory implements MutationInterceptorFactory {

    private static final FeatureParameter FILE = FeatureParameter.named("file")
        .withDescription("The file listing the changed lines");

    @Override
    public String description() {
        return "Only the mutants on changed lines";
    }

    @Override
    public Feature provides() {
        return Feature.named("CHANGED")
            .withDescription(description())
            .withOnByDefault(false)
            .withParameter(FILE);
    }

    @Override
    public MutationInterceptor createInterceptor(InterceptorParameters params) {
        Path file = Path.of(params.getString(FILE).orElseThrow(() -> new IllegalArgumentException("CHANGED needs file[...]")));
        try {
            return new ChangedLines(new HashSet<>(Files.readAllLines(file)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class ChangedLines implements MutationInterceptor {
        private final Set<String> lines;

        ChangedLines(Set<String> lines) {
            this.lines = lines;
        }

        @Override
        public InterceptorType type() {
            return InterceptorType.FILTER;
        }

        @Override
        public void begin(ClassTree clazz) {
        }

        @Override
        public Collection<MutationDetails> intercept(Collection<MutationDetails> mutations, Mutater m) {
            return mutations.stream().filter(this::changed).filter(it -> !isJunk(it)).collect(Collectors.toList());
        }

        @Override
        public void end() {
        }

        private boolean changed(MutationDetails mutation) {
            String className = mutation.getClassName().asInternalName();
            String folder = className.substring(0, className.lastIndexOf('/') + 1);
            return lines.contains(folder + mutation.getFilename() + ":" + mutation.getLineNumber());
        }

        /**
         * Kotlin's own null checks, which no test of the app's behavior can tell are gone, and what
         * Compose adds to a composable to skip it and trace it, which only makes it slower.
         */
        private static boolean isJunk(MutationDetails mutation) {
            String description = mutation.getDescription();
            return description.contains("kotlin/jvm/internal/Intrinsics")
                || description.contains("androidx/compose/runtime/Composer")
                || description.contains("androidx/compose/runtime/ComposerKt");
        }
    }
}
