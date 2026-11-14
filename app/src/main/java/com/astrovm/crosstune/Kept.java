package com.astrovm.crosstune;

import kotlin.ranges.IntRange;
import kotlin.text.CharsKt;
import kotlin.text.StringsKt;

/**
 * A text cut short: {@code text} up to {@code end}, then {@code after}, what followed a tag cut off its end, as the
 * line break that ended it. Read as one text without copying it, so a loop that cuts tags off a long title's end one
 * at a time reads near that end only. It's in Java, as a Kotlin CharSequence answers {@code length()} and
 * {@code charAt()} through bridges to its own names, which mutation testing changes but never sees a test run.
 */
final class Kept implements CharSequence {
    private final String text;
    final int end;
    private final String after;

    Kept(String text, int end, String after) {
        this.text = text;
        this.end = end;
        this.after = after;
    }

    @Override
    public int length() {
        return end + after.length();
    }

    @Override
    public char charAt(int index) {
        return index < end ? text.charAt(index) : after.charAt(index - end);
    }

    // What's asked of the title, then what's asked of what follows it: either may be none.
    @Override
    public CharSequence subSequence(int start, int stop) {
        return text.substring(Math.min(start, end), Math.min(stop, end)) + after.substring(Math.max(start - end, 0), Math.max(stop - end, 0));
    }

    @Override
    public String toString() {
        return text.substring(0, end) + after;
    }

    /** Without {@code range}, which is near the end. */
    Kept without(IntRange range) {
        int cut = Math.min(range.getFirst(), end);
        return new Kept(text, cut, subSequence(cut, range.getFirst()) + subSequence(range.getLast() + 1, length()).toString());
    }

    /**
     * Without the spaces it ends with, as Kotlin's {@code trim} takes them, and those it starts with once all but
     * {@code after} is cut.
     */
    Kept trimmed() {
        String rest = StringsKt.trimEnd(after).toString();
        int cut = end;
        if (rest.isEmpty()) {
            while (cut > 0 && CharsKt.isWhitespace(text.charAt(cut - 1))) cut--;
        }
        return new Kept(text, cut, cut == 0 ? StringsKt.trimStart(rest).toString() : rest);
    }
}
