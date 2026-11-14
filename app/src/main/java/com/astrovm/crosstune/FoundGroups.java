package com.astrovm.crosstune;

import java.util.AbstractList;
import kotlin.ranges.IntRange;
import kotlin.text.MatchGroup;
import kotlin.text.MatchGroupCollection;

/**
 * The groups of a match in {@code input}: where each starts and ends, a pair after another, -1 for a group that took
 * no part. It's in Java, as a Kotlin collection answers {@code contains()} through bridges to its own, which mutation
 * testing changes but never sees a test run.
 */
final class FoundGroups extends AbstractList<MatchGroup> implements MatchGroupCollection {
    private final CharSequence input;
    private final int[] bounds;

    FoundGroups(CharSequence input, int[] bounds) {
        this.input = input;
        this.bounds = bounds;
    }

    @Override
    public int size() {
        return bounds.length / 2;
    }

    @Override
    public MatchGroup get(int index) {
        int start = bounds[2 * index];
        if (start < 0) return null;
        int end = bounds[2 * index + 1];
        return new MatchGroup(input.subSequence(start, end).toString(), new IntRange(start, end - 1));
    }
}
