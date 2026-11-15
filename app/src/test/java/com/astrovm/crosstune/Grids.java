package com.astrovm.crosstune;

/**
 * A new array of arrays, made by one instruction of its own, which Java writes and Kotlin never does: for
 * {@link GrowthTest}, which counts what each level holds.
 */
final class Grids {
    private Grids() {
    }

    static int[][] of(int rows, int columns) {
        return new int[rows][columns];
    }
}
